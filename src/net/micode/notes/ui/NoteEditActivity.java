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
 * 处理便签编辑
 */
package net.micode.notes.ui;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.SearchManager;
import android.appwidget.AppWidgetManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.BackgroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.TextNote;
import net.micode.notes.model.WorkingNote;
import net.micode.notes.model.WorkingNote.NoteSettingChangedListener;
import net.micode.notes.tool.DataUtils;
import net.micode.notes.tool.ResourceParser;
import net.micode.notes.tool.ResourceParser.TextAppearanceResources;
import net.micode.notes.ui.DateTimePickerDialog.OnDateTimeSetListener;
import net.micode.notes.ui.NoteEditText.OnTextViewChangeListener;
import net.micode.notes.widget.NoteWidgetProvider_2x;
import net.micode.notes.widget.NoteWidgetProvider_4x;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class NoteEditActivity extends Activity implements OnClickListener,
        NoteSettingChangedListener, OnTextViewChangeListener {
    // 顶部区域内的控件
    private class HeadViewHolder {
        // 最后修改时间
        public TextView tvModified;

        // 闹钟/提醒状态
        public ImageView ivAlertIcon;

        // 显示提醒时间
        public TextView tvAlertDate;

        // 背景颜色
        public ImageView ibSetBgColor;
    }

    // 资源映射
    // 按钮与颜色
    private static final Map<Integer, Integer> sBgSelectorBtnsMap = new HashMap<Integer, Integer>();
    static {
        sBgSelectorBtnsMap.put(R.id.iv_bg_yellow, ResourceParser.YELLOW);
        sBgSelectorBtnsMap.put(R.id.iv_bg_red, ResourceParser.RED);
        sBgSelectorBtnsMap.put(R.id.iv_bg_blue, ResourceParser.BLUE);
        sBgSelectorBtnsMap.put(R.id.iv_bg_green, ResourceParser.GREEN);
        sBgSelectorBtnsMap.put(R.id.iv_bg_white, ResourceParser.WHITE);
    }

    // 选中状态与颜色
    private static final Map<Integer, Integer> sBgSelectorSelectionMap = new HashMap<Integer, Integer>();
    static {
        sBgSelectorSelectionMap.put(ResourceParser.YELLOW, R.id.iv_bg_yellow_select);
        sBgSelectorSelectionMap.put(ResourceParser.RED, R.id.iv_bg_red_select);
        sBgSelectorSelectionMap.put(ResourceParser.BLUE, R.id.iv_bg_blue_select);
        sBgSelectorSelectionMap.put(ResourceParser.GREEN, R.id.iv_bg_green_select);
        sBgSelectorSelectionMap.put(ResourceParser.WHITE, R.id.iv_bg_white_select);
    }

    // 按钮与字号
    private static final Map<Integer, Integer> sFontSizeBtnsMap = new HashMap<Integer, Integer>();
    static {
        sFontSizeBtnsMap.put(R.id.ll_font_large, ResourceParser.TEXT_LARGE);
        sFontSizeBtnsMap.put(R.id.ll_font_small, ResourceParser.TEXT_SMALL);
        sFontSizeBtnsMap.put(R.id.ll_font_normal, ResourceParser.TEXT_MEDIUM);
        sFontSizeBtnsMap.put(R.id.ll_font_super, ResourceParser.TEXT_SUPER);
    }

    // 选中状态与字号
    private static final Map<Integer, Integer> sFontSelectorSelectionMap = new HashMap<Integer, Integer>();
    static {
        sFontSelectorSelectionMap.put(ResourceParser.TEXT_LARGE, R.id.iv_large_select);
        sFontSelectorSelectionMap.put(ResourceParser.TEXT_SMALL, R.id.iv_small_select);
        sFontSelectorSelectionMap.put(ResourceParser.TEXT_MEDIUM, R.id.iv_medium_select);
        sFontSelectorSelectionMap.put(ResourceParser.TEXT_SUPER, R.id.iv_super_select);
    }

    private static final String TAG = "NoteEditActivity";

    // 顶部区域所有控件
    private HeadViewHolder mNoteHeaderHolder;

    // 顶部面板容器
    private View mHeadViewPanel;

    // 背景颜色选择控件
    private View mNoteBgColorSelector;

    // 字体大小选择控件
    private View mFontSizeSelector;

    // 文本输入控件
    private EditText mNoteEditor;

    // 编辑区域容器
    private View mNoteEditorPanel;

    // 笔记对象，记录便签状态，负责与数据库交互
    private WorkingNote mWorkingNote;

    // 偏好设置
    private SharedPreferences mSharedPrefs;
    // 字体大小
    private int mFontSizeId;

    private static final String PREFERENCE_FONT_SIZE = "pref_font_size";

    // 桌面快捷方式标题最大长度
    private static final int SHORTCUT_ICON_TITLE_MAX_LEN = 10;

    // 待办项完成与否的字符图标
    public static final String TAG_CHECKED = String.valueOf('\u221A');
    public static final String TAG_UNCHECKED = String.valueOf('\u25A1');

    //清单模式下的视图容器
    private LinearLayout mEditTextList;

    // 查询字符串
    private String mUserQuery;
    // 查询字符串对应的正则表达式
    private Pattern mPattern;

    // 创建时的初始化方法
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 基础ui绑定
        super.onCreate(savedInstanceState);
        this.setContentView(R.layout.note_edit);

        // 检查是否应该初始化
        if (savedInstanceState == null && !initActivityState(getIntent())) {
            finish();
            return;
        }
        initResources();
    }

    /**
     * Current activity may be killed when the memory is low. Once it is killed, for another time
     * user load this activity, we should restore the former state
     */
    // 进程被意外关闭时储存现场信息
    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        // 存储信息
        if (savedInstanceState != null && savedInstanceState.containsKey(Intent.EXTRA_UID)) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.putExtra(Intent.EXTRA_UID, savedInstanceState.getLong(Intent.EXTRA_UID));
            if (!initActivityState(intent)) {
                finish();
                return;
            }
            Log.d(TAG, "Restoring from killed activity");
        }
    }

    // 根据任务显示界面
    private boolean initActivityState(Intent intent) {
        /**
         * If the user specified the {@link Intent#ACTION_VIEW} but not provided with id,
         * then jump to the NotesListActivity
         */
        // 指定了查看操作，但没有提供笔记id，则跳转至笔记列表界面
        mWorkingNote = null;
        // 指定ACTION_VIEW（查看与编辑）
        if (TextUtils.equals(Intent.ACTION_VIEW, intent.getAction())) {
            // 接受笔记id
            long noteId = intent.getLongExtra(Intent.EXTRA_UID, 0);
            mUserQuery = "";

            /**
             * Starting from the searched result
             */
            // 从搜索结果跳转而来
            if (intent.hasExtra(SearchManager.EXTRA_DATA_KEY)) {
                // 接收真正的笔记id
                noteId = Long.parseLong(intent.getStringExtra(SearchManager.EXTRA_DATA_KEY));
                // 获取查询字符串
                mUserQuery = intent.getStringExtra(SearchManager.USER_QUERY);
            }

            // 在可见的便签中没有任务要求的笔记id
            if (!DataUtils.visibleInNoteDatabase(getContentResolver(), noteId, Notes.TYPE_NOTE)) {
                // 接下来会跳转到主界面
                Intent jump = new Intent(this, NotesListActivity.class);
                startActivity(jump);
                // 提示便签不存在
                showToast(R.string.error_note_not_exist);
                finish();
                return false;
            // 找到了便签
            } else {
                // 加载笔记
                mWorkingNote = WorkingNote.load(this, noteId);
                // 如果没加载到则输出记录
                if (mWorkingNote == null) {
                    // 输出日志直接结束
                    Log.e(TAG, "load note failed with note id" + noteId);
                    finish();
                    return false;
                }
            }
            // 调整软键盘输入模式
            getWindow().setSoftInputMode(
                    // 默认隐藏软键盘
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                            //软键盘打开时自动调整窗口大小
                            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        // 指定INSERT_OR_EDIT（新建便签）
        } else if(TextUtils.equals(Intent.ACTION_INSERT_OR_EDIT, intent.getAction())) {
            // New note
            // 新建笔记
            long folderId = intent.getLongExtra(Notes.INTENT_EXTRA_FOLDER_ID, 0);
            int widgetId = intent.getIntExtra(Notes.INTENT_EXTRA_WIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            int widgetType = intent.getIntExtra(Notes.INTENT_EXTRA_WIDGET_TYPE,
                    Notes.TYPE_WIDGET_INVALIDE);
            int bgResId = intent.getIntExtra(Notes.INTENT_EXTRA_BACKGROUND_ID,
                    ResourceParser.getDefaultBgId(this));

            // Parse call-record note
            // 通话便签相关
            String phoneNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
            long callDate = intent.getLongExtra(Notes.INTENT_EXTRA_CALL_DATE, 0);
            // 是通话便签
            if (callDate != 0 && phoneNumber != null) {
                // 电话号码为空
                if (TextUtils.isEmpty(phoneNumber)) {
                    // 输出waring日志
                    Log.w(TAG, "The call record number is null");
                }
                long noteId = 0;
                // 该通话便签在数据库中已有
                if ((noteId = DataUtils.getNoteIdByPhoneNumberAndCallDate(getContentResolver(),
                        phoneNumber, callDate)) > 0) {
                    // 加载已有通话便签
                    mWorkingNote = WorkingNote.load(this, noteId);
                    //没加载到
                    if (mWorkingNote == null) {
                        // 输出error日志
                        Log.e(TAG, "load call note failed with note id" + noteId);
                        finish();
                        return false;
                    }
                // 通话便签在数据库中没有
                } else {
                    // 创建空普通笔记
                    mWorkingNote = WorkingNote.createEmptyNote(this, folderId, widgetId,
                            widgetType, bgResId);
                    // 将普通笔记转为通话笔记
                    mWorkingNote.convertToCallNote(phoneNumber, callDate);
                }
            // 是普通笔记
            } else {
                // 创建普通空笔记
                mWorkingNote = WorkingNote.createEmptyNote(this, folderId, widgetId, widgetType,
                        bgResId);
            }
            // 调整软键盘输入模式
            getWindow().setSoftInputMode(
                    //软键盘打开时自动调整窗口大小
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                            // 软键盘自动弹出
                            | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        // 其他动作
        } else {
            // 输出error日志
            Log.e(TAG, "Intent not specified action, should not support");
            finish();
            return false;
        }
        // 设置监听器
        mWorkingNote.setOnSettingStatusChangedListener(this);
        return true;
    }

    // 进入前台交互
    @Override
    protected void onResume() {
        super.onResume();
        // 渲染ui
        initNoteScreen();
    }

    // 渲染ui
    private void initNoteScreen() {
        // 设置字号
        mNoteEditor.setTextAppearance(this, TextAppearanceResources
                .getTexAppearanceResource(mFontSizeId));
        // 列表模式
        if (mWorkingNote.getCheckListMode() == TextNote.MODE_CHECK_LIST) {
            // 切换至列表模式
            switchToListMode(mWorkingNote.getContent());
        // 普通模式
        } else {
            // 设置文字，高亮搜索结果
            mNoteEditor.setText(getHighlightQueryResult(mWorkingNote.getContent(), mUserQuery));
            // 光标移至末尾
            mNoteEditor.setSelection(mNoteEditor.getText().length());
        }
        // 隐藏所有背景资源
        for (Integer id : sBgSelectorSelectionMap.keySet()) {
            findViewById(sBgSelectorSelectionMap.get(id)).setVisibility(View.GONE);
        }
        // 设置顶部和编辑区背景
        mHeadViewPanel.setBackgroundResource(mWorkingNote.getTitleBgResId());
        mNoteEditorPanel.setBackgroundResource(mWorkingNote.getBgColorResId());

        // 显示最后修改时间
        mNoteHeaderHolder.tvModified.setText(DateUtils.formatDateTime(this,
                mWorkingNote.getModifiedDate(), DateUtils.FORMAT_SHOW_DATE
                        | DateUtils.FORMAT_NUMERIC_DATE | DateUtils.FORMAT_SHOW_TIME
                        | DateUtils.FORMAT_SHOW_YEAR));

        /**
         * TODO: Add the menu for setting alert. Currently disable it because the DateTimePicker
         * is not ready
         */
        // 展示顶部闹钟提醒图标
        showAlertHeader();
    }

    private void showAlertHeader() {
        // 设置了闹钟提醒
        if (mWorkingNote.hasClockAlert()) {
            long time = System.currentTimeMillis();
            // 提醒时间已过
            if (time > mWorkingNote.getAlertDate()) {
                // 提醒文本框显示已过期
                mNoteHeaderHolder.tvAlertDate.setText(R.string.note_alert_expired);
            // 提醒时间未到
            } else {
                // 提醒文本框显示还有多久提醒
                mNoteHeaderHolder.tvAlertDate.setText(DateUtils.getRelativeTimeSpanString(
                        mWorkingNote.getAlertDate(), time, DateUtils.MINUTE_IN_MILLIS));
            }
            mNoteHeaderHolder.tvAlertDate.setVisibility(View.VISIBLE);
            mNoteHeaderHolder.ivAlertIcon.setVisibility(View.VISIBLE);
        // 没设置闹钟提醒
        } else {
            // 不显示图标
            mNoteHeaderHolder.tvAlertDate.setVisibility(View.GONE);
            mNoteHeaderHolder.ivAlertIcon.setVisibility(View.GONE);
        };
    }

    //设置新任务
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        initActivityState(intent);
    }

    // 保存任务状态
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        /**
         * For new note without note id, we should firstly save it to
         * generate a id. If the editing note is not worth saving, there
         * is no id which is equivalent to create new note
         */
        // 没保存过的笔记先进行保存以生成id
        if (!mWorkingNote.existInDatabase()) {
            saveNote();
        }
        // 保存id
        outState.putLong(Intent.EXTRA_UID, mWorkingNote.getNoteId());
        // 输出日志
        Log.d(TAG, "Save working note id: " + mWorkingNote.getNoteId() + " onSaveInstanceState");
    }

    // 点击外部区域时
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // 处理背景颜色
        if (mNoteBgColorSelector.getVisibility() == View.VISIBLE
                && !inRangeOfView(mNoteBgColorSelector, ev)) {
            // 隐藏可见并且没点击到区域内的笔记背景
            mNoteBgColorSelector.setVisibility(View.GONE);
            return true;
        }

        //同理
        if (mFontSizeSelector.getVisibility() == View.VISIBLE
                && !inRangeOfView(mFontSizeSelector, ev)) {
            mFontSizeSelector.setVisibility(View.GONE);
            return true;
        }
        // 关闭其他控件
        return super.dispatchTouchEvent(ev);
    }

    // 判断点击位置是否在控件内，简单易懂懒得写了
    private boolean inRangeOfView(View view, MotionEvent ev) {
        int []location = new int[2];
        view.getLocationOnScreen(location);
        int x = location[0];
        int y = location[1];
        if (ev.getX() < x
                || ev.getX() > (x + view.getWidth())
                || ev.getY() < y
                || ev.getY() > (y + view.getHeight())) {
                    return false;
                }
        return true;
    }

    //初始化资源
    private void initResources() {
        // 初始化一大堆控件，挂载监听器
        mHeadViewPanel = findViewById(R.id.note_title);
        mNoteHeaderHolder = new HeadViewHolder();
        mNoteHeaderHolder.tvModified = (TextView) findViewById(R.id.tv_modified_date);
        mNoteHeaderHolder.ivAlertIcon = (ImageView) findViewById(R.id.iv_alert_icon);
        mNoteHeaderHolder.tvAlertDate = (TextView) findViewById(R.id.tv_alert_date);
        mNoteHeaderHolder.ibSetBgColor = (ImageView) findViewById(R.id.btn_set_bg_color);
        mNoteHeaderHolder.ibSetBgColor.setOnClickListener(this);
        mNoteEditor = (EditText) findViewById(R.id.note_edit_view);
        mNoteEditorPanel = findViewById(R.id.sv_note_edit);
        mNoteBgColorSelector = findViewById(R.id.note_bg_color_selector);

        for (int id : sBgSelectorBtnsMap.keySet()) {
            ImageView iv = (ImageView) findViewById(id);
            iv.setOnClickListener(this);
        }

        mFontSizeSelector = findViewById(R.id.font_size_selector);
        for (int id : sFontSizeBtnsMap.keySet()) {
            View view = findViewById(id);
            view.setOnClickListener(this);
        };
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mFontSizeId = mSharedPrefs.getInt(PREFERENCE_FONT_SIZE, ResourceParser.BG_DEFAULT_FONT_SIZE);
        /**
         * HACKME: Fix bug of store the resource id in shared preference.
         * The id may larger than the length of resources, in this case,
         * return the {@link ResourceParser#BG_DEFAULT_FONT_SIZE}
         */
        if(mFontSizeId >= TextAppearanceResources.getResourcesSize()) {
            mFontSizeId = ResourceParser.BG_DEFAULT_FONT_SIZE;
        }
        mEditTextList = (LinearLayout) findViewById(R.id.note_edit_list);
    }

    // 离开界面时
    @Override
    protected void onPause() {
        super.onPause();
        // 如果保存了笔记
        if(saveNote()) {
            Log.d(TAG, "Note data was saved with length:" + mWorkingNote.getContent().length());
        }
        // 清除设置状态
        clearSettingState();
    }

    // 更新桌面控件
    private void updateWidget() {
        // 创建桌面控件变化任务
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        // 根据控件大小设置任务空间大小属性
        if (mWorkingNote.getWidgetType() == Notes.TYPE_WIDGET_2X) {
            intent.setClass(this, NoteWidgetProvider_2x.class);
        } else if (mWorkingNote.getWidgetType() == Notes.TYPE_WIDGET_4X) {
            intent.setClass(this, NoteWidgetProvider_4x.class);
        } else {
            Log.e(TAG, "Unspported widget type");
            return;
        }

        //设置任务控件id属性
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[] {
            mWorkingNote.getWidgetId()
        });

        // 广播任务
        sendBroadcast(intent);
        // 返回成功信号
        setResult(RESULT_OK, intent);
    }

    // 点击事件
    public void onClick(View v) {
        int id = v.getId();
        // 点击的是背景颜色修改按钮
        if (id == R.id.btn_set_bg_color) {
            // 打开背景颜色设置面板
            mNoteBgColorSelector.setVisibility(View.VISIBLE);
            findViewById(sBgSelectorSelectionMap.get(mWorkingNote.getBgColorId())).setVisibility(
                    -                    View.VISIBLE);
        // 点击的是背景颜色设置按钮
        } else if (sBgSelectorBtnsMap.containsKey(id)) {
            // ui切换
            findViewById(sBgSelectorSelectionMap.get(mWorkingNote.getBgColorId())).setVisibility(
                    View.GONE);
            // 更新数据
            mWorkingNote.setBgColorId(sBgSelectorBtnsMap.get(id));
            // 隐藏背景颜色设置面板
            mNoteBgColorSelector.setVisibility(View.GONE);
        // 点击的是字体大小设置按钮
        } else if (sFontSizeBtnsMap.containsKey(id)) {
            // ui切换
            findViewById(sFontSelectorSelectionMap.get(mFontSizeId)).setVisibility(View.GONE);
            mFontSizeId = sFontSizeBtnsMap.get(id);
            // 保存到偏好数据
            mSharedPrefs.edit().putInt(PREFERENCE_FONT_SIZE, mFontSizeId).commit();
            findViewById(sFontSelectorSelectionMap.get(mFontSizeId)).setVisibility(View.VISIBLE);
            // 清单模式
            if (mWorkingNote.getCheckListMode() == TextNote.MODE_CHECK_LIST) {
                getWorkingText();
                switchToListMode(mWorkingNote.getContent());
            // 普通模式
            } else {
                mNoteEditor.setTextAppearance(this,
                        TextAppearanceResources.getTexAppearanceResource(mFontSizeId));
            }
            // 关闭字体选择面板
            mFontSizeSelector.setVisibility(View.GONE);
        }
    }

    // 按下返回键时
    @Override
    public void onBackPressed() {
        // 背景颜色设置面板/字体设置面板打开时，退出这些面板
        if(clearSettingState()) {
            return;
        }
        // 如果没打开上述面板，则保存退出这条笔记
        saveNote();
        super.onBackPressed();
    }

    // 退出背景颜色设置面板/字体设置面板
    private boolean clearSettingState() {
        if (mNoteBgColorSelector.getVisibility() == View.VISIBLE) {
            mNoteBgColorSelector.setVisibility(View.GONE);
            return true;
        } else if (mFontSizeSelector.getVisibility() == View.VISIBLE) {
            mFontSizeSelector.setVisibility(View.GONE);
            return true;
        }
        return false;
    }

    // 修改背景颜色
    public void onBackgroundColorChanged() {
        // 隐藏旧背景颜色
        findViewById(sBgSelectorSelectionMap.get(mWorkingNote.getBgColorId())).setVisibility(
                View.VISIBLE);
        // 显示新的背景颜色
        mNoteEditorPanel.setBackgroundResource(mWorkingNote.getBgColorResId());
        mHeadViewPanel.setBackgroundResource(mWorkingNote.getTitleBgResId());
    }

    // 打开菜单时
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // 正在销毁
        if (isFinishing()) {
            return true;
        }
        // 退出面板，清空已有菜单项
        clearSettingState();
        menu.clear();
        // 通话笔记面板
        if (mWorkingNote.getFolderId() == Notes.ID_CALL_RECORD_FOLDER) {
            getMenuInflater().inflate(R.menu.call_note_edit, menu);
        // 普通笔记面板
        } else {
            getMenuInflater().inflate(R.menu.note_edit, menu);
        }
        // 列表模式切换
        if (mWorkingNote.getCheckListMode() == TextNote.MODE_CHECK_LIST) {
            menu.findItem(R.id.menu_list_mode).setTitle(R.string.menu_normal_mode);
        } else {
            menu.findItem(R.id.menu_list_mode).setTitle(R.string.menu_list_mode);
        }
        // 闹钟提醒
        if (mWorkingNote.hasClockAlert()) {
            // 有闹钟时隐藏设置闹钟
            menu.findItem(R.id.menu_alert).setVisible(false);
        } else {
            // 没有时隐藏删除闹钟
            menu.findItem(R.id.menu_delete_remind).setVisible(false);
        }
        return true;
    }

    // 选项选择处理
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            // 新建笔记
            case R.id.menu_new_note:
                createNewNote();
                break;
            // 删除笔记
            case R.id.menu_delete:
                // 弹出删除确认对话框
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle(getString(R.string.alert_title_delete));
                builder.setIcon(android.R.drawable.ic_dialog_alert);
                builder.setMessage(getString(R.string.alert_message_delete_note));
                builder.setPositiveButton(android.R.string.ok,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                deleteCurrentNote();
                                finish();
                            }
                        });
                builder.setNegativeButton(android.R.string.cancel, null);
                builder.show();
                break;
            // 设置字体大小
            case R.id.menu_font_size:
                mFontSizeSelector.setVisibility(View.VISIBLE);
                findViewById(sFontSelectorSelectionMap.get(mFontSizeId)).setVisibility(View.VISIBLE);
                break;
            // 列表模式切换
            case R.id.menu_list_mode:
                mWorkingNote.setCheckListMode(mWorkingNote.getCheckListMode() == 0 ?
                        TextNote.MODE_CHECK_LIST : 0);
                break;
            // 分享笔记
            case R.id.menu_share:
                getWorkingText();
                sendTo(this, mWorkingNote.getContent());
                break;
            // 在桌面创建控件
            case R.id.menu_send_to_desktop:
                sendToDesktop();
                break;
            // 设置提醒
            case R.id.menu_alert:
                setReminder();
                break;
            // 删除提醒
            case R.id.menu_delete_remind:
                mWorkingNote.setAlertDate(0, false);
                break;
            default:
                break;
        }
        return true;
    }

    // 设置提醒
    private void setReminder() {
        // 弹出设置提醒时间对话框
        DateTimePickerDialog d = new DateTimePickerDialog(this, System.currentTimeMillis());
        d.setOnDateTimeSetListener(new OnDateTimeSetListener() {
            public void OnDateTimeSet(AlertDialog dialog, long date) {
                mWorkingNote.setAlertDate(date	, true);
            }
        });
        d.show();
    }

    /**
     * Share note to apps that support {@link Intent#ACTION_SEND} action
     * and {@text/plain} type
     */
    // 分享笔记
    private void sendTo(Context context, String info) {
        // 新建发送任务
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.putExtra(Intent.EXTRA_TEXT, info);
        intent.setType("text/plain");
        context.startActivity(intent);
    }

    // 创建新笔记
    private void createNewNote() {
        // Firstly, save current editing notes
        // 保存当前编辑的笔记
        saveNote();

        // For safety, start a new NoteEditActivity
        // 为了安全性，finish后启动一个新的自身进程
        finish();
        Intent intent = new Intent(this, NoteEditActivity.class);
        intent.setAction(Intent.ACTION_INSERT_OR_EDIT);
        intent.putExtra(Notes.INTENT_EXTRA_FOLDER_ID, mWorkingNote.getFolderId());
        startActivity(intent);
    }

    // 删除笔记
    private void deleteCurrentNote() {
        // 只删除存在于数据库中的文件
        if (mWorkingNote.existInDatabase()) {
            HashSet<Long> ids = new HashSet<Long>();
            long id = mWorkingNote.getNoteId();
            // 只删除根目录之外的便签
            if (id != Notes.ID_ROOT_FOLDER) {
                ids.add(id);
            } else {
                Log.d(TAG, "Wrong note id, should not happen");
            }
            // 本地模式
            if (!isSyncMode()) {
                // 物理删除
                if (!DataUtils.batchDeleteNotes(getContentResolver(), ids)) {
                    Log.e(TAG, "Delete Note error");
                }
            // 同步模式
            } else {
                // 逻辑删除
                if (!DataUtils.batchMoveToFolder(getContentResolver(), ids, Notes.ID_TRASH_FOLER)) {
                    Log.e(TAG, "Move notes to trash folder error, should not happens");
                }
            }
        }
        // 内存中标记删除
        mWorkingNote.markDeleted(true);
    }

    // 是否为同步模式
    private boolean isSyncMode() {
        return NotesPreferenceActivity.getSyncAccountName(this).trim().length() > 0;
    }

    // 闹钟提醒修改时触发
    public void onClockAlertChanged(long date, boolean set) {
        /**
         * User could set clock to an unsaved note, so before setting the
         * alert clock, we should save the note first
         */
        // 如果没有保存过，则先进行保存
        if (!mWorkingNote.existInDatabase()) {
            saveNote();
        }
        if (mWorkingNote.getNoteId() > 0) {
            // 创建任务
            Intent intent = new Intent(this, AlarmReceiver.class);
            intent.setData(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, mWorkingNote.getNoteId()));
            PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, intent, 0);
            AlarmManager alarmManager = ((AlarmManager) getSystemService(ALARM_SERVICE));
            showAlertHeader();
            if(!set) {
                // 如果取消了闹钟则发送取消提醒任务
                alarmManager.cancel(pendingIntent);
            } else {
                // 如果设置了闹钟则发送设置提醒任务
                alarmManager.set(AlarmManager.RTC_WAKEUP, date, pendingIntent);
            }
        } else {
            /**
             * There is the condition that user has input nothing (the note is
             * not worthy saving), we have no note id, remind the user that he
             * should input something
             */
            // 用户对空白便签创建闹钟
            Log.e(TAG, "Clock alert setting error");
            showToast(R.string.error_note_empty_for_clock);
        }
    }

    // 控件变化
    public void onWidgetChanged() {
        updateWidget();
    }

    // 列表模式下删除
    public void onEditTextDelete(int index, String text) {
        // 获得列表项数量
        int childCount = mEditTextList.getChildCount();
        // 只有一项时不允许删除
        if (childCount == 1) {
            return;
        }

        // 重排索引
        for (int i = index + 1; i < childCount; i++) {
            ((NoteEditText) mEditTextList.getChildAt(i).findViewById(R.id.et_edit_text))
                    .setIndex(i - 1);
        }

        // 从容器中移除
        mEditTextList.removeViewAt(index);
        // 移动光标
        NoteEditText edit = null;
        if(index == 0) {
            edit = (NoteEditText) mEditTextList.getChildAt(0).findViewById(
                    R.id.et_edit_text);
        } else {
            edit = (NoteEditText) mEditTextList.getChildAt(index - 1).findViewById(
                    R.id.et_edit_text);
        }
        //修改文字显示
        int length = edit.length();
        edit.append(text);
        edit.requestFocus();
        edit.setSelection(length);
    }

    // 输入回车
    public void onEditTextEnter(int index, String text) {
        /**
         * Should not happen, check for debug
         */
        // 理论上不应该发生索引越界
        if(index > mEditTextList.getChildCount()) {
            Log.e(TAG, "Index out of mEditTextList boundrary, should not happen");
        }

        // 建立新视图放入文本
        View view = getListItem(text, index);
        mEditTextList.addView(view, index);
        NoteEditText edit = (NoteEditText) view.findViewById(R.id.et_edit_text);
        // 请求输入焦点
        edit.requestFocus();
        // 光标放在文字最前面
        edit.setSelection(0);
        // 重排索引
        for (int i = index + 1; i < mEditTextList.getChildCount(); i++) {
            ((NoteEditText) mEditTextList.getChildAt(i).findViewById(R.id.et_edit_text))
                    .setIndex(i);
        }
    }

    // 切换至列表模式
    private void switchToListMode(String text) {
        // 移除列表模式容器中已有的内容
        mEditTextList.removeAllViews();
        // 以回车分割文本为条目
        String[] items = text.split("\n");
        int index = 0;
        // 把条目转化为列表项
        for (String item : items) {
            if(!TextUtils.isEmpty(item)) {
                mEditTextList.addView(getListItem(item, index));
                index++;
            }
        }
        // 追加空白行在末尾，将光标移动到末尾空白行上
        mEditTextList.addView(getListItem("", index));
        mEditTextList.getChildAt(index).findViewById(R.id.et_edit_text).requestFocus();

        // 显示列表模式面板，隐藏普通模式面板
        mNoteEditor.setVisibility(View.GONE);
        mEditTextList.setVisibility(View.VISIBLE);
    }

    // 获取高亮搜索结果
    private Spannable getHighlightQueryResult(String fullText, String userQuery) {
        // 处理文本字符串防止空字符串崩溃
        SpannableString spannable = new SpannableString(fullText == null ? "" : fullText);
        // 用户正在查询（查询字符串非空）
        if (!TextUtils.isEmpty(userQuery)) {
            // 变异查询字符串为正则表达式
            mPattern = Pattern.compile(userQuery);
            // 根据正则表达式查找
            Matcher m = mPattern.matcher(fullText);
            int start = 0;
            // 循环查找所有结果
            while (m.find(start)) {
                spannable.setSpan(
                        new BackgroundColorSpan(this.getResources().getColor(
                                R.color.user_query_highlight)), m.start(), m.end(),
                        Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
                start = m.end();
            }
        }
        return spannable;
    }

    // 处理列表控件
    private View getListItem(String item, int index) {
        // 加载模板，统一字号
        View view = LayoutInflater.from(this).inflate(R.layout.note_edit_list_item, null);
        final NoteEditText edit = (NoteEditText) view.findViewById(R.id.et_edit_text);
        edit.setTextAppearance(this, TextAppearanceResources.getTexAppearanceResource(mFontSizeId));
        CheckBox cb = ((CheckBox) view.findViewById(R.id.cb_edit_item));
        // 复选框改变触发器
        cb.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                // 被勾选（已完成）
                if (isChecked) {
                    // 开启删除线
                    edit.setPaintFlags(edit.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                // 未勾选（未完成）
                } else {
                    // 直接赋值，打开抗锯齿和字体微调
                    edit.setPaintFlags(Paint.ANTI_ALIAS_FLAG | Paint.DEV_KERN_TEXT_FLAG);
                }
            }
        });

        // 如果以选中符开头（也就是被选中）
        if (item.startsWith(TAG_CHECKED)) {
            // 复选框勾选
            cb.setChecked(true);
            // 开启删除线
            edit.setPaintFlags(edit.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            // 去掉开头的选中符
            item = item.substring(TAG_CHECKED.length(), item.length()).trim();
        // 如果以未选中符开头（也就是未被选中）
        } else if (item.startsWith(TAG_UNCHECKED)) {
            // 复选框不勾选
            cb.setChecked(false);
            // 取消删除线（恢复正常字体）
            edit.setPaintFlags(Paint.ANTI_ALIAS_FLAG | Paint.DEV_KERN_TEXT_FLAG);
            // 去掉开头的未选中符
            item = item.substring(TAG_UNCHECKED.length(), item.length()).trim();
        }

        // 绑定监听
        edit.setOnTextViewChangeListener(this);
        // 设置光标
        edit.setIndex(index);
        // 设置搜索高亮
        edit.setText(getHighlightQueryResult(item, mUserQuery));
        return view;
    }

    // 文本变化
    public void onTextChange(int index, boolean hasText) {
        // 索引越界
        if (index >= mEditTextList.getChildCount()) {
            Log.e(TAG, "Wrong index, should not happen");
            return;
        }
        // 仍有文本则显示左侧复选框，否则不显示
        if(hasText) {
            mEditTextList.getChildAt(index).findViewById(R.id.cb_edit_item).setVisibility(View.VISIBLE);
        } else {
            mEditTextList.getChildAt(index).findViewById(R.id.cb_edit_item).setVisibility(View.GONE);
        }
    }

    // 显示模式改变时
    public void onCheckListModeChanged(int oldMode, int newMode) {
        // 普通模式 -> 列表模式
        if (newMode == TextNote.MODE_CHECK_LIST) {
            // 调用切换至列表模式的逻辑
            switchToListMode(mNoteEditor.getText().toString());
        // 列表模式 -> 普通模式
        } else {
            // 没有被选中的列表项
            if (!getWorkingText()) {
                // 清除所有的标记是否选中的字符
                mWorkingNote.setWorkingText(mWorkingNote.getContent().replace(TAG_UNCHECKED + " ",
                        ""));
            }
            // 有被选中的列表项时保留标记字符
            // 高亮
            mNoteEditor.setText(getHighlightQueryResult(mWorkingNote.getContent(), mUserQuery));
            // 隐藏列表面板，显示普通面板
            mEditTextList.setVisibility(View.GONE);
            mNoteEditor.setVisibility(View.VISIBLE);
        }
    }

    // 获取当前文本
    private boolean getWorkingText() {
        boolean hasChecked = false;
        // 当前模式是列表模式
        if (mWorkingNote.getCheckListMode() == TextNote.MODE_CHECK_LIST) {
            // 构造字符串
            StringBuilder sb = new StringBuilder();
            // 遍历列表项
            for (int i = 0; i < mEditTextList.getChildCount(); i++) {
                View view = mEditTextList.getChildAt(i);
                NoteEditText edit = (NoteEditText) view.findViewById(R.id.et_edit_text);
                if (!TextUtils.isEmpty(edit.getText())) {
                    // 如果被选中，则在前面加入选中符，空格分割，末尾加入回车
                    if (((CheckBox) view.findViewById(R.id.cb_edit_item)).isChecked()) {
                        sb.append(TAG_CHECKED).append(" ").append(edit.getText()).append("\n");
                        // 列表内有被选中的项
                        hasChecked = true;
                    // 如果未被选中，则在前面加入未选中符，空格分割，末尾加入回车
                    } else {
                        sb.append(TAG_UNCHECKED).append(" ").append(edit.getText()).append("\n");
                    }
                }
            }
            // 返回构造好的字符串
            mWorkingNote.setWorkingText(sb.toString());
        // 当前模式是普通模式
        } else {
            // 直接返回文本
            mWorkingNote.setWorkingText(mNoteEditor.getText().toString());
        }
        return hasChecked;
    }

    // 保存笔记
    private boolean saveNote() {
        // 获取文本
        getWorkingText();
        // 保存便签，
        boolean saved = mWorkingNote.saveNote();
        // 如果保存成功
        if (saved) {
            /**
             * There are two modes from List view to edit view, open one note,
             * create/edit a node. Opening node requires to the original
             * position in the list when back from edit view, while creating a
             * new node requires to the top of the list. This code
             * {@link #RESULT_OK} is used to identify the create/edit state
             */
            // 为了识别创建后保存和打开已有后保存链接RESULT_OK，以区分返回后列表项不同位置
            setResult(RESULT_OK);
        }
        return saved;
    }

    // 设置桌面控件
    private void sendToDesktop() {
        /**
         * Before send message to home, we should make sure that current
         * editing note is exists in databases. So, for new note, firstly
         * save it
         */
        // 如果未保存过，保存笔记以获得id
        if (!mWorkingNote.existInDatabase()) {
            saveNote();
        }

        // 已有id时
        if (mWorkingNote.getNoteId() > 0) {
            // 创建新建快捷方式的任务
            Intent sender = new Intent();
            Intent shortcutIntent = new Intent(this, NoteEditActivity.class);
            shortcutIntent.setAction(Intent.ACTION_VIEW);
            shortcutIntent.putExtra(Intent.EXTRA_UID, mWorkingNote.getNoteId());
            // 点击桌面便签时任务
            sender.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent);
            sender.putExtra(Intent.EXTRA_SHORTCUT_NAME,
                    makeShortcutIconTitle(mWorkingNote.getContent()));
            // 标题和图标
            sender.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                    Intent.ShortcutIconResource.fromContext(this, R.drawable.icon_app));
            // 允许存在相同app的多个桌面控件
            sender.putExtra("duplicate", true);
            //广播
            sender.setAction("com.android.launcher.action.INSTALL_SHORTCUT");
            // 弹出提示框
            showToast(R.string.info_note_enter_desktop);
            sendBroadcast(sender);
        } else {
            /**
             * There is the condition that user has input nothing (the note is
             * not worthy saving), we have no note id, remind the user that he
             * should input something
             */
            // 不能发送空便签到桌面
            Log.e(TAG, "Send to desktop error");
            showToast(R.string.error_note_empty_for_send_to_desktop);
        }
    }

    // 获取较短的标题
    private String makeShortcutIconTitle(String content) {
        // 移除选中符/未选中符
        content = content.replace(TAG_CHECKED, "");
        content = content.replace(TAG_UNCHECKED, "");
        // 超过最大长度戒断，不超过直接返回原标题
        return content.length() > SHORTCUT_ICON_TITLE_MAX_LEN ? content.substring(0,
                SHORTCUT_ICON_TITLE_MAX_LEN) : content;
    }

    // 弹出提示框的重载方法
    private void showToast(int resId) {
        showToast(resId, Toast.LENGTH_SHORT);
    }

    private void showToast(int resId, int duration) {
        Toast.makeText(this, resId, duration).show();
    }
}
