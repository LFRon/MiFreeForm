package com.buildsession.betterYAMF.ipc;

import com.buildsession.betterYAMF.common.model.AppInfo;

interface IAppListCallback {
    void onAppListReceived(in List<AppInfo> appList);
    void onAppListFinished();
}
