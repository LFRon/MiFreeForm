package android.window;

import android.app.ActivityManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.view.SurfaceControl;

// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/window/ITaskOrganizer.aidl
public interface ITaskOrganizer extends IInterface {
    void addStartingWindow(StartingWindowInfo info);
    void removeStartingWindow(StartingWindowRemovalInfo removalInfo);
    void copySplashScreenView(int taskId);
    void onAppSplashScreenViewRemoved(int taskId);
    void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash);
    void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo);
    void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo);
    void onBackPressedOnTaskRoot(ActivityManager.RunningTaskInfo taskInfo);
    void onImeDrawnOnTask(int taskId);

    abstract class Stub extends Binder implements ITaskOrganizer {
        @Override
        public IBinder asBinder() {
            throw new UnsupportedOperationException();
        }
    }
}
