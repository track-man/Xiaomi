/*
 * Copyright (c) 2010-2011, The MiCode Open Source Community (www.micode.net)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * 用于闹钟提醒的接收
 */
package net.micode.notes.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;


public class AlarmReceiver extends BroadcastReceiver {
    //到达提醒时间后调用的方法
    @Override
    public void onReceive(Context context, Intent intent) {
        // 设置接下来由AlarmAlertActivity处理
        intent.setClass(context, AlarmAlertActivity.class);
        // 添加FLAG_ACTIVITY_NEW_TASK标志位，用于为AlarmAlertActivity创建新的任务栈
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // 执行任务
        context.startActivity(intent);
    }
}
