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
 * 4×4桌面组件
 */
package net.micode.notes.widget;

import android.appwidget.AppWidgetManager;
import android.content.Context;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.tool.ResourceParser;


public class NoteWidgetProvider_4x extends NoteWidgetProvider {
    // 更新组件
    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        super.update(context, appWidgetManager, appWidgetIds);
    }

    // 返回布局id
    protected int getLayoutId() {
        return R.layout.widget_4x;
    }

    // 返回背景资源id
    @Override
    protected int getBgResourceId(int bgId) {
        return ResourceParser.WidgetBgResources.getWidget4xBgResource(bgId);
    }

    // 返回组件类型
    @Override
    protected int getWidgetType() {
        return Notes.TYPE_WIDGET_4X;
    }
}
