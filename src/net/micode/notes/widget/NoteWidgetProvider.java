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
 * 桌面组件抽象类
 */
package net.micode.notes.widget;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.util.Log;
import android.widget.RemoteViews;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.tool.ResourceParser;
import net.micode.notes.ui.NoteEditActivity;
import net.micode.notes.ui.NotesListActivity;

public abstract class NoteWidgetProvider extends AppWidgetProvider {
    // 投影
    public static final String [] PROJECTION = new String [] {
        NoteColumns.ID,
        NoteColumns.BG_COLOR_ID,
        NoteColumns.SNIPPET
    };

    // 下标常量
    public static final int COLUMN_ID           = 0;
    public static final int COLUMN_BG_COLOR_ID  = 1;
    public static final int COLUMN_SNIPPET      = 2;

    private static final String TAG = "NoteWidgetProvider";

    // 组件被删除时触发
    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        // 准备更新的组件id
        ContentValues values = new ContentValues();
        values.put(NoteColumns.WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        // 遍历更新
        for (int i = 0; i < appWidgetIds.length; i++) {
            // UPDATE
            /**
             *    UPDATE  _id, bg_color_id, snippet
             *    FROM    notes
             *    WHERE   widget_id = [appWidgetId]
             */
            context.getContentResolver().update(Notes.CONTENT_NOTE_URI,
                    values,
                    NoteColumns.WIDGET_ID + "=?",
                    new String[] { String.valueOf(appWidgetIds[i])});
        }
    }

    // 数据库查询
    private Cursor getNoteWidgetInfo(Context context, int widgetId) {
        // SELECT
        /**
         *    SELECT  _id, bg_color_id, snippet
         *    FROM    notes
         *    WHERE   widget_id = [appWidgetId] AND parent_id <> -3
         */
        return context.getContentResolver().query(Notes.CONTENT_NOTE_URI,
                PROJECTION,
                NoteColumns.WIDGET_ID + "=? AND " + NoteColumns.PARENT_ID + "<>?",
                new String[] { String.valueOf(widgetId), String.valueOf(Notes.ID_TRASH_FOLER) },
                null);
    }

    // 刷新组件
    protected void update(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        update(context, appWidgetManager, appWidgetIds, false);
    }

    private void update(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds,
            boolean privacyMode) {
        // 遍历组件
        for (int i = 0; i < appWidgetIds.length; i++) {
            // 只处理有效的桌面组件
            if (appWidgetIds[i] != AppWidgetManager.INVALID_APPWIDGET_ID) {
                // 初始化背景颜色，内容
                int bgId = ResourceParser.getDefaultBgId(context);
                String snippet = "";
                // 创建跳转至编辑界面的任务
                Intent intent = new Intent(context, NoteEditActivity.class);
                // 设置启动模式
                intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                // 任务携带组件id和组件类型
                intent.putExtra(Notes.INTENT_EXTRA_WIDGET_ID, appWidgetIds[i]);
                intent.putExtra(Notes.INTENT_EXTRA_WIDGET_TYPE, getWidgetType());

                // 从数据库中获取组件
                Cursor c = getNoteWidgetInfo(context, appWidgetIds[i]);
                // 找到组件
                if (c != null && c.moveToFirst()) {
                    // 一条笔记只能对应一个组件
                    if (c.getCount() > 1) {
                        Log.e(TAG, "Multiple message with same widget id:" + appWidgetIds[i]);
                        c.close();
                        return;
                    }
                    // 获取内容，加入任务
                    snippet = c.getString(COLUMN_SNIPPET);
                    bgId = c.getInt(COLUMN_BG_COLOR_ID);
                    intent.putExtra(Intent.EXTRA_UID, c.getLong(COLUMN_ID));
                    intent.setAction(Intent.ACTION_VIEW);
                // 未找到组件
                } else {
                    // 设置占位符内容
                    snippet = context.getResources().getString(R.string.widget_havenot_content);
                    // 任务改为新建笔记
                    intent.setAction(Intent.ACTION_INSERT_OR_EDIT);
                }

                if (c != null) {
                    c.close();
                }

                // 远程视图，设置背景
                RemoteViews rv = new RemoteViews(context.getPackageName(), getLayoutId());
                rv.setImageViewResource(R.id.widget_bg_image, getBgResourceId(bgId));
                intent.putExtra(Notes.INTENT_EXTRA_BACKGROUND_ID, bgId);
                /**
                 * Generate the pending intent to start host for the widget
                 */
                // 生成pendingIntent用于启动组件的详情界面
                PendingIntent pendingIntent = null;
                // 隐私模式下
                if (privacyMode) {
                    // 无法查看笔记内容
                    rv.setTextViewText(R.id.widget_text,
                            context.getString(R.string.widget_under_visit_mode));
                    // 组件详情界面改为笔记列表界面
                    pendingIntent = PendingIntent.getActivity(context, appWidgetIds[i], new Intent(
                            context, NotesListActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
                // 非隐私模式下
                } else {
                    // 设置笔记内容
                    rv.setTextViewText(R.id.widget_text, snippet);
                    // 组件详情界面是组件的笔记界面
                    pendingIntent = PendingIntent.getActivity(context, appWidgetIds[i], intent,
                            PendingIntent.FLAG_UPDATE_CURRENT);
                }

                rv.setOnClickPendingIntent(R.id.widget_text, pendingIntent);
                appWidgetManager.updateAppWidget(appWidgetIds[i], rv);
            }
        }
    }

    // getter方法
    protected abstract int getBgResourceId(int bgId);

    protected abstract int getLayoutId();

    protected abstract int getWidgetType();
}
