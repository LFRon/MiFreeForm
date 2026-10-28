package com.buildsession.betterYAMF.xposed.utils

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Reflection helpers for the platform adapters.
 *
 * Modules that target libxposed API 102 cannot resolve `de.robv.android.xposed` classes, so the
 * classic helper surface is reimplemented here on plain `java.lang.reflect`. Overload selection
 * follows the classic cost model: exact type < boxed/unboxed type < widening reference conversion <
 * narrowing reference conversion. The declaring class wins ties because the hierarchy is walked
 * from the receiver's class upwards and only strictly cheaper matches replace.
 */
@Suppress("unused")
object Reflect {
    // Hooks run on input, binder and animation threads; the classic helper's plain HashMaps are
    // not safe under that concurrency.
    private val methodCache = java.util.concurrent.ConcurrentHashMap<String, Method>()
    private val constructorCache = java.util.concurrent.ConcurrentHashMap<String, Constructor<*>>()
    private val fieldCache = java.util.concurrent.ConcurrentHashMap<String, Field>()

    fun callMethod(obj: Any?, methodName: String, vararg args: Any?): Any? {
        val receiver = checkNotNull(obj) { "$methodName needs a receiver" }
        return findMethodBestMatch(receiver.javaClass, methodName, *typesOf(args)).invoke(receiver, *args)
    }

    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? =
        findMethodBestMatch(clazz, methodName, *typesOf(args)).invoke(null, *args)

    /**
     * Explicit-parameter-type form of [callMethod]. Needed where runtime argument types cannot
     * select the overload: a `null` argument, or a `boolean` flag beside a nullable token.
     *
     * The classic helper expressed this as an overload; Kotlin cannot disambiguate two vararg
     * candidates here, so the typed entry point has its own name.
     */
    fun callMethodTyped(
        obj: Any?,
        methodName: String,
        parameterTypes: Array<out Class<*>?>,
        vararg args: Any?,
    ): Any? {
        val receiver = checkNotNull(obj) { "$methodName needs a receiver" }
        return findMethodBestMatch(receiver.javaClass, methodName, *parameterTypes).invoke(receiver, *args)
    }

    fun callStaticMethodTyped(
        clazz: Class<*>,
        methodName: String,
        parameterTypes: Array<out Class<*>?>,
        vararg args: Any?,
    ): Any? = findMethodBestMatch(clazz, methodName, *parameterTypes).invoke(null, *args)

    fun newInstance(clazz: Class<*>, vararg args: Any?): Any =
        findConstructorBestMatch(clazz, typesOf(args)).newInstance(*args)

    fun newInstanceTyped(
        clazz: Class<*>,
        parameterTypes: Array<out Class<*>?>,
        vararg args: Any?,
    ): Any {
        val types: Array<Class<*>?> = Array(parameterTypes.size) { parameterTypes[it] }
        return findConstructorBestMatch(clazz, types).newInstance(*args)
    }

    fun getObjectField(obj: Any?, fieldName: String): Any? =
        findField(checkNotNull(obj) { "$fieldName needs a receiver" }.javaClass, fieldName).get(obj)

    fun getIntField(obj: Any?, fieldName: String): Int =
        intOf(findField(checkNotNull(obj) { "$fieldName needs a receiver" }.javaClass, fieldName), obj)

    fun getBooleanField(obj: Any?, fieldName: String): Boolean =
        getObjectField(obj, fieldName) as? Boolean ?: false

    fun setObjectField(obj: Any?, fieldName: String, value: Any?) {
        findField(checkNotNull(obj) { "$fieldName needs a receiver" }.javaClass, fieldName).set(obj, value)
    }

    fun setIntField(obj: Any?, fieldName: String, value: Int) {
        val field = findField(checkNotNull(obj) { "$fieldName needs a receiver" }.javaClass, fieldName)
        if (field.type == Int::class.javaPrimitiveType) field.setInt(obj, value) else field.set(obj, value)
    }

    fun setBooleanField(obj: Any?, fieldName: String, value: Boolean) {
        val field = findField(checkNotNull(obj) { "$fieldName needs a receiver" }.javaClass, fieldName)
        if (field.type == Boolean::class.javaPrimitiveType) field.setBoolean(obj, value)
        else field.set(obj, value)
    }

    fun getStaticObjectField(clazz: Class<*>, fieldName: String): Any? =
        findField(clazz, fieldName).get(null)

    fun getStaticIntField(clazz: Class<*>, fieldName: String): Int =
        intOf(findField(clazz, fieldName), null)

    fun getStaticBooleanField(clazz: Class<*>, fieldName: String): Boolean =
        getStaticObjectField(clazz, fieldName) as? Boolean ?: false

    /** Same as [getObjectField] for members the platform guarantees, such as WM's global lock. */
    fun requireObjectField(obj: Any?, fieldName: String): Any =
        checkNotNull(getObjectField(obj, fieldName)) {
            "${obj?.javaClass?.name}#$fieldName is null"
        }

    fun findMethodBestMatch(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>?): Method {
        val types = parameterTypes.exact()
        val key = cacheKey(clazz, methodName, types, "bestmatch")
        methodCache[key]?.let { return it }

        if (types.none { it == null }) {
            findMethodExactIfExists(clazz, methodName, *types)?.let {
                methodCache[key] = it
                return it
            }
        }

        var bestMatch: Method? = null
        var bestCost = Float.MAX_VALUE
        var current: Class<*>? = clazz
        while (current != null) {
            for (method in current.declaredMethods) {
                if (method.name != methodName) continue
                val cost = parametersCost(method.parameterTypes, types)
                if (cost >= 0f && cost < bestCost) {
                    bestMatch = method
                    bestCost = cost
                }
            }
            current = current.superclass
        }
        val resolved = bestMatch ?: throw NoSuchMethodError(
            clazz.name + "#" + methodName + parametersString(types)
        )
        resolved.isAccessible = true
        methodCache[key] = resolved
        return resolved
    }

    fun findMethodExactIfExists(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypes: Class<*>?,
    ): Method? {
        val types = parameterTypes.exact()
        if (types.any { it == null }) return null
        val key = cacheKey(clazz, methodName, types, "exact")
        methodCache[key]?.let { return it }
        return try {
            @Suppress("UNCHECKED_CAST")
            val method = clazz.getDeclaredMethod(methodName, *(types as Array<Class<*>>))
            method.isAccessible = true
            methodCache[key] = method
            method
        } catch (_: NoSuchMethodException) {
            null
        }
    }

    fun findFieldIfExists(clazz: Class<*>, fieldName: String): Field? =
        runCatching { findField(clazz, fieldName) }.getOrNull()

    fun findField(clazz: Class<*>, fieldName: String): Field {
        val key = clazz.name + "#" + fieldName
        fieldCache[key]?.let { return it }
        var current: Class<*>? = clazz
        while (current != null) {
            current.declaredFields.firstOrNull { it.name == fieldName }?.let {
                it.isAccessible = true
                fieldCache[key] = it
                return it
            }
            for (iface in current.interfaces) {
                findFieldOrNull(iface, fieldName)?.let {
                    fieldCache[key] = it
                    return it
                }
            }
            current = current.superclass
        }
        throw NoSuchFieldError(clazz.name + "#" + fieldName)
    }

    private fun findFieldOrNull(clazz: Class<*>, fieldName: String): Field? {
        clazz.declaredFields.firstOrNull { it.name == fieldName }?.let {
            it.isAccessible = true
            return it
        }
        for (iface in clazz.interfaces) findFieldOrNull(iface, fieldName)?.let { return it }
        return clazz.superclass?.let { findFieldOrNull(it, fieldName) }
    }

    private fun findConstructorBestMatch(
        clazz: Class<*>,
        parameterTypes: Array<Class<*>?>,
    ): Constructor<*> {
        val key = cacheKey(clazz, "<init>", parameterTypes, "bestmatch")
        constructorCache[key]?.let { return it }

        if (parameterTypes.none { it == null }) {
            @Suppress("UNCHECKED_CAST")
            val exact = try {
                clazz.getDeclaredConstructor(*(parameterTypes as Array<Class<*>>))
            } catch (_: NoSuchMethodException) {
                null
            }
            if (exact != null) {
                exact.isAccessible = true
                constructorCache[key] = exact
                return exact
            }
        }

        var bestMatch: Constructor<*>? = null
        var bestCost = Float.MAX_VALUE
        for (constructor in clazz.declaredConstructors) {
            val cost = parametersCost(constructor.parameterTypes, parameterTypes)
            if (cost >= 0f && cost < bestCost) {
                bestMatch = constructor
                bestCost = cost
            }
        }
        val resolved = bestMatch ?: throw NoSuchMethodError(clazz.name + parametersString(parameterTypes))
        resolved.isAccessible = true
        constructorCache[key] = resolved
        return resolved
    }

    private fun parametersCost(parameters: Array<Class<*>>, arguments: Array<Class<*>?>): Float {
        if (parameters.size == arguments.size) {
            var cost = 0f
            for (index in parameters.indices) {
                val single = parameterCost(parameters[index], arguments[index])
                if (single < 0f) return -1f
                cost += single
            }
            return cost
        }
        // A trailing array parameter is tried as varargs when the arity differs, matching the
        // classic helper: `f(Object...)` accepts `f(a, b)` as well as `f(new Object[]{a, b})`.
        if (parameters.isEmpty() || arguments.size < parameters.size - 1) return -1f
        val last = parameters.last()
        if (!last.isArray) return -1f
        val fixed = parameters.size - 1
        var cost = 0f
        for (index in 0 until fixed) {
            val single = parameterCost(parameters[index], arguments[index])
            if (single < 0f) return -1f
            cost += single
        }
        val component = checkNotNull(last.componentType)
        for (index in fixed until arguments.size) {
            val single = parameterCost(component, arguments[index])
            if (single < 0f) return -1f
            cost += single
        }
        return cost
    }

    private fun parameterCost(parameter: Class<*>, argument: Class<*>?): Float {
        if (argument == null) return if (parameter.isPrimitive) -1f else 1f
        if (parameter == argument) return 0f
        val boxedParameter = boxed(parameter)
        val boxedArgument = boxed(argument)
        if (boxedParameter == boxedArgument) return 0.5f
        if (parameter.isAssignableFrom(argument)) return 1f
        if (boxedParameter.isAssignableFrom(boxedArgument)) return 1f
        if (argument.isAssignableFrom(parameter)) return 2f
        if (boxedArgument.isAssignableFrom(boxedParameter)) return 2f
        return -1f
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Void.TYPE -> java.lang.Void::class.java
        else -> type
    }

    private fun intOf(field: Field, receiver: Any?): Int = when (val value = field.get(receiver)) {
        is Number -> value.toInt()
        null -> throw NullPointerException("${field.declaringClass.name}#${field.name} is null")
        else -> throw IllegalArgumentException(
            "${field.declaringClass.name}#${field.name} is ${value.javaClass.name}, not numeric"
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun Array<out Class<*>?>.exact(): Array<Class<*>?> = this as Array<Class<*>?>

    private fun typesOf(args: Array<out Any?>): Array<Class<*>?> =
        Array(args.size) { index -> args[index]?.javaClass }

    private fun cacheKey(
        clazz: Class<*>,
        name: String,
        parameterTypes: Array<Class<*>?>,
        kind: String,
    ): String = clazz.name + "#" + name + parametersString(parameterTypes) + "#" + kind

    private fun parametersString(parameterTypes: Array<Class<*>?>): String =
        parameterTypes.joinToString(",", "(", ")") { it?.name ?: "*" }
}

/** Finds the first declared method matching [predicate]; the predicate runs with the method as receiver. */
internal fun findMethod(clazz: Class<*>, predicate: Method.() -> Boolean): Method {
    for (method in clazz.declaredMethods) {
        if (predicate(method)) {
            method.isAccessible = true
            return method
        }
    }
    throw NoSuchMethodException("${clazz.name} has no method matching the supplied predicate")
}

internal val Method.paramCount: Int get() = parameterCount

internal fun args(vararg values: Any?): Array<Any?> = arrayOf(*values)

internal fun argTypes(vararg types: Class<*>?): Array<Class<*>?> = arrayOf(*types)

internal fun Any?.invokeMethod(
    name: String,
    args: Array<out Any?>? = null,
    argTypes: Array<out Class<*>?>? = null,
): Any? {
    val receiver = checkNotNull(this) { "$name needs a receiver" }
    val values = args ?: emptyArray()
    val types: Array<Class<*>?> = if (argTypes != null) {
        Array(argTypes.size) { index -> argTypes[index] }
    } else {
        Array(values.size) { index -> values[index]?.javaClass }
    }
    return Reflect.findMethodBestMatch(receiver.javaClass, name, *types).invoke(receiver, *values)
}

internal inline fun <reified T> Any?.invokeMethodAs(
    name: String,
    args: Array<out Any?>? = null,
    argTypes: Array<out Class<*>?>? = null,
): T? = invokeMethod(name, args, argTypes) as? T

internal fun Any?.getObject(name: String): Any? = Reflect.getObjectField(this, name)

internal inline fun <reified T> Any?.getObjectAs(name: String): T = getObject(name) as T
