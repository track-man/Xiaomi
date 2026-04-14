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

package net.micode.notes.ui;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;

import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.NoteColumns;
/*
 * 用于闹钟提醒启动消息的接收
 */

public class AlarmInitReceiver extends BroadcastReceiver {

    // 投影
    private static final String [] PROJECTION = new String [] {
        NoteColumns.ID,
        NoteColumns.ALERTED_DATE
    };

    // 闹钟id和提醒时间
    private static final int COLUMN_ID                = 0;
    private static final int COLUMN_ALERTED_DATE      = 1;

    // 接收到消息时的处理
    @Override
    public void onReceive(Context context, Intent intent) {
        // 查询当前日期
        long currentDate = System.currentTimeMillis();
        // 查询提醒日期在当前日期后的笔记
        /*
         *   SELECT    _id,alert_date
         *   FROM      notes
         *   WHERE     alert_date > [currentDate] AND type = 0
         *   ORDER BY  null
         */
        Cursor c = context.getContentResolver().query(Notes.CONTENT_NOTE_URI,
                PROJECTION,
                NoteColumns.ALERTED_DATE + ">? AND " + NoteColumns.TYPE + "=" + Notes.TYPE_NOTE,
                new String[] { String.valueOf(currentDate) },
                null);

        if (c != null) {
            if (c.moveToFirst()) {
                do {
                    // 获取提醒日期
                    long alertDate = c.getLong(COLUMN_ALERTED_DATE);
                    // 指定闹钟响起后由AlarmReciver处理后续逻辑
                    Intent sender = new Intent(context, AlarmReceiver.class);
                    // 为任务绑定当前uri
                    sender.setData(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, c.getLong(COLUMN_ID)));
                    // 包装任务，传递给系统
                    PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, sender, 0);
                    // 调用系统闹钟服务，提交闹钟
                    AlarmManager alermManager = (AlarmManager) context
                            .getSystemService(Context.ALARM_SERVICE);
                    alermManager.set(AlarmManager.RTC_WAKEUP, alertDate, pendingIntent);
                } while (c.moveToNext());
            }
            c.close();
        }
    }
}
