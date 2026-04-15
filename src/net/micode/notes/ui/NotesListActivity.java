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
 * 主界面
 */
package net.micode.notes.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.appwidget.AppWidgetManager;
import android.content.AsyncQueryHandler;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.ActionMode;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnCreateContextMenuListener;
import android.view.View.OnTouchListener;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.gtask.remote.GTaskSyncService;
import net.micode.notes.model.WorkingNote;
import net.micode.notes.tool.BackupUtils;
import net.micode.notes.tool.DataUtils;
import net.micode.notes.tool.ResourceParser;
import net.micode.notes.ui.NotesListAdapter.AppWidgetAttribute;
import net.micode.notes.widget.NoteWidgetProvider_2x;
import net.micode.notes.widget.NoteWidgetProvider_4x;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;

public class NotesListActivity extends Activity implements OnClickListener, OnItemLongClickListener {
    // 查询令牌
    // 用于查询文件夹下所有笔记
    private static final int FOLDER_NOTE_LIST_QUERY_TOKEN = 0;

    // 用于查询所有文件夹
    private static final int FOLDER_LIST_QUERY_TOKEN      = 1;

    // 文件菜单索引
    // 删除
    private static final int MENU_FOLDER_DELETE = 0;

    // 查看
    private static final int MENU_FOLDER_VIEW = 1;

    // 重命名
    private static final int MENU_FOLDER_CHANGE_NAME = 2;

    // 首选项键名
    private static final String PREFERENCE_ADD_INTRODUCTION = "net.micode.notes.introduction";

    // 界面状态枚举：根文件夹，子文件夹，通话记录文件夹
    private enum ListEditState {
        NOTE_LIST, SUB_FOLDER, CALL_RECORD_FOLDER
    };

    // 界面状态对象
    private ListEditState mState;

    // 异步数据库查询对象
    private BackgroundQueryHandler mBackgroundQueryHandler;

    // 与数据库的链接器
    private NotesListAdapter mNotesListAdapter;

    // 主列表容器
    private ListView mNotesListView;

    // 新建笔记按钮
    private Button mAddNewNote;

    // 事件分发标志，用于手动分发事件
    private boolean mDispatch;

    // 触摸位置y坐标
    private int mOriginY;

    // 分发后的y坐标
    private int mDispatchY;

    // 顶部标题栏视图
    private TextView mTitleBar;

    // 文件夹id
    private long mCurrentFolderId;

    // 内容解析器
    private ContentResolver mContentResolver;

    // 多选模式回调控制
    private ModeCallback mModeCallBack;

    private static final String TAG = "NotesListActivity";

    // 列表滚动速度
    public static final int NOTES_LISTVIEW_SCROLL_RATE = 30;

    // 焦点笔记对象
    private NoteItemData mFocusNoteDataItem;

    // 普通查询条件
    //  parent_id = ?
    private static final String NORMAL_SELECTION = NoteColumns.PARENT_ID + "=?";

    // 根目录查询条件
    //  ( type <> 2 AND parent_id = ? ) OR ( id = -1 AND count > 0 )
    private static final String ROOT_FOLDER_SELECTION = "(" + NoteColumns.TYPE + "<>"
            + Notes.TYPE_SYSTEM + " AND " + NoteColumns.PARENT_ID + "=?)" + " OR ("
            + NoteColumns.ID + "=" + Notes.ID_CALL_RECORD_FOLDER + " AND "
            + NoteColumns.NOTES_COUNT + ">0)";

    // 跳转请求码
    private final static int REQUEST_CODE_OPEN_NODE = 102;
    private final static int REQUEST_CODE_NEW_NODE  = 103;

    // 创建时触发
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 主界面加载note_list布局
        setContentView(R.layout.note_list);
        // 初始化资源
        initResources();

        /**
         * Insert an introduction when user firstly use this application
         */
        // 创建引导便签插入数据库
        setAppInfoFromRawRes();
    }

    // 从编辑界面返回主列表时触发
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // 编辑操作已完成
        if (resultCode == RESULT_OK
                // 返回原因是打开便签/新建便签
                && (requestCode == REQUEST_CODE_OPEN_NODE || requestCode == REQUEST_CODE_NEW_NODE)) {
            mNotesListAdapter.changeCursor(null);
        } else {
            // 如果不是该方法需要处理的逻辑则向上传递
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    // 从资源中加载引导
    private void setAppInfoFromRawRes() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        // 仅在首次进入应用时出现
        if (!sp.getBoolean(PREFERENCE_ADD_INTRODUCTION, false)) {
            StringBuilder sb = new StringBuilder();
            InputStream in = null;
            try {
                // 从资源中加载输入流
                 in = getResources().openRawResource(R.raw.introduction);
                if (in != null) {
                    // 读取输入流，构建字符串
                    InputStreamReader isr = new InputStreamReader(in);
                    BufferedReader br = new BufferedReader(isr);
                    char [] buf = new char[1024];
                    int len = 0;
                    while ((len = br.read(buf)) > 0) {
                        sb.append(buf, 0, len);
                    }
                } else {
                    Log.e(TAG, "Read introduction file error");
                    return;
                }
            } catch (IOException e) {
                e.printStackTrace();
                return;
            } finally {
                if(in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
            }

            // 创建一个新便签，内容是构建的引导字符串
            WorkingNote note = WorkingNote.createEmptyNote(this, Notes.ID_ROOT_FOLDER,
                    AppWidgetManager.INVALID_APPWIDGET_ID, Notes.TYPE_WIDGET_INVALIDE,
                    ResourceParser.RED);
            note.setWorkingText(sb.toString());
            // 保存笔记后，首选项键置true
            if (note.saveNote()) {
                sp.edit().putBoolean(PREFERENCE_ADD_INTRODUCTION, true).commit();
            } else {
                Log.e(TAG, "Save introduction note error");
                return;
            }
        }
    }

    // 界面可见时触发
    @Override
    protected void onStart() {
        super.onStart();
        // 异步加载数据
        startAsyncNotesListQuery();
    }

    // 资源初始化
    private void initResources() {
        // 获取内容解析器
        mContentResolver = this.getContentResolver();
        // 初始化数据库查询对象
        mBackgroundQueryHandler = new BackgroundQueryHandler(this.getContentResolver());
        // 当前目录设为根目录
        mCurrentFolderId = Notes.ID_ROOT_FOLDER;
        // 获取控件布局实例，向其中加入底部视图
        mNotesListView = (ListView) findViewById(R.id.notes_list);
        mNotesListView.addFooterView(LayoutInflater.from(this).inflate(R.layout.note_list_footer, null),
                null, false);
        // 绑定监听器和适配器
        mNotesListView.setOnItemClickListener(new OnListItemClickListener());
        mNotesListView.setOnItemLongClickListener(this);
        mNotesListAdapter = new NotesListAdapter(this);
        mNotesListView.setAdapter(mNotesListAdapter);
        // 获取按钮控件实例，绑定监听器
        mAddNewNote = (Button) findViewById(R.id.btn_new_note);
        mAddNewNote.setOnClickListener(this);
        mAddNewNote.setOnTouchListener(new NewNoteOnTouchListener());
        // 界面初始化
        mDispatch = false;
        mDispatchY = 0;
        mOriginY = 0;
        mTitleBar = (TextView) findViewById(R.id.tv_title_bar);
        mState = ListEditState.NOTE_LIST;
        mModeCallBack = new ModeCallback();
    }

    // 多选模式处理
    private class ModeCallback implements ListView.MultiChoiceModeListener, OnMenuItemClickListener {
        // 控件实例
        private DropdownMenu mDropDownMenu;
        private ActionMode mActionMode;
        private MenuItem mMoveMenu;

        // 初始化
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            getMenuInflater().inflate(R.menu.note_list_options, menu);
            menu.findItem(R.id.delete).setOnMenuItemClickListener(this);
            mMoveMenu = menu.findItem(R.id.move);
            // 电话笔记或者是系统笔记不显示移动选项
            if (mFocusNoteDataItem.getParentId() == Notes.ID_CALL_RECORD_FOLDER
                    || DataUtils.getUserFolderCount(mContentResolver) == 0) {
                mMoveMenu.setVisible(false);
            } else {
                mMoveMenu.setVisible(true);
                mMoveMenu.setOnMenuItemClickListener(this);
            }
            mActionMode = mode;
            // 打开勾选项
            mNotesListAdapter.setChoiceMode(true);
            // 禁止长按触发
            mNotesListView.setLongClickable(false);
            // 隐藏新建按钮
            mAddNewNote.setVisibility(View.GONE);

            // 上下文操作栏放入自定义视图
            View customView = LayoutInflater.from(NotesListActivity.this).inflate(
                    R.layout.note_list_dropdown_menu, null);
            mode.setCustomView(customView);
            mDropDownMenu = new DropdownMenu(NotesListActivity.this,
                    (Button) customView.findViewById(R.id.selection_menu),
                    R.menu.note_list_dropdown);
            mDropDownMenu.setOnDropdownMenuItemClickListener(new PopupMenu.OnMenuItemClickListener(){
                // 全选和取消全选逻辑切换
                public boolean onMenuItemClick(MenuItem item) {
                    mNotesListAdapter.selectAll(!mNotesListAdapter.isAllSelected());
                    updateMenu();
                    return true;
                }

            });
            return true;
        }

        // 菜单变化时触发
        private void updateMenu() {
            // 更新被选中的数量
            int selectedCount = mNotesListAdapter.getSelectedCount();
            // Update dropdown menu
            // 标题更新
            String format = getResources().getString(R.string.menu_select_title, selectedCount);
            mDropDownMenu.setTitle(format);
            // 全选和取消全选按钮切换
            MenuItem item = mDropDownMenu.findItem(R.id.action_select_all);
            if (item != null) {
                // 列表项被全部选中时切换为取消全选
                if (mNotesListAdapter.isAllSelected()) {
                    item.setChecked(true);
                    item.setTitle(R.string.menu_deselect_all);
                // 否则显示全选
                } else {
                    item.setChecked(false);
                    item.setTitle(R.string.menu_select_all);
                }
            }
        }

        // 相关逻辑已在别处处理，所以永远返回false
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            // TODO Auto-generated method stub
            return false;
        }

        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            // TODO Auto-generated method stub
            return false;
        }

        // 上下文菜单关闭时触发
        public void onDestroyActionMode(ActionMode mode) {
            // 选项框消失
            mNotesListAdapter.setChoiceMode(false);
            // 打开长按功能
            mNotesListView.setLongClickable(true);
            // 显示新建按钮
            mAddNewNote.setVisibility(View.VISIBLE);
        }

        // 上下文菜单结束时最后触发
        public void finishActionMode() {
            mActionMode.finish();
        }

        // 列表项选中状态改变时触发
        public void onItemCheckedStateChanged(ActionMode mode, int position, long id,
                boolean checked) {
            // 更新选中状态
            mNotesListAdapter.setCheckedItem(position, checked);
            // 更新菜单
            updateMenu();
        }

        // 菜单项被点击时触发
        public boolean onMenuItemClick(MenuItem item) {
            // 没有被选中的菜单项
            if (mNotesListAdapter.getSelectedCount() == 0) {
                // 弹出提示框
                Toast.makeText(NotesListActivity.this, getString(R.string.menu_select_none),
                        Toast.LENGTH_SHORT).show();
                return true;
            }

            // 被选中的菜单项
            switch (item.getItemId()) {
                // 批量删除
                case R.id.delete:
                    // 二次确认对话框
                    AlertDialog.Builder builder = new AlertDialog.Builder(NotesListActivity.this);
                    builder.setTitle(getString(R.string.alert_title_delete));
                    builder.setIcon(android.R.drawable.ic_dialog_alert);
                    builder.setMessage(getString(R.string.alert_message_delete_notes,
                                             mNotesListAdapter.getSelectedCount()));
                    builder.setPositiveButton(android.R.string.ok,
                                             new DialogInterface.OnClickListener() {
                                                //确认删除后触发
                                                 public void onClick(DialogInterface dialog,
                                                         int which) {
                                                     // 批量删除
                                                     batchDelete();
                                                 }
                                             });
                    builder.setNegativeButton(android.R.string.cancel, null);
                    builder.show();
                    break;
                // 批量移动
                case R.id.move:
                    // 搜索目标文件夹
                    startQueryDestinationFolders();
                    break;
                default:
                    return false;
            }
            return true;
        }
    }

    // 新建按钮触发
    private class NewNoteOnTouchListener implements OnTouchListener {

        // 触摸时
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                // 按下
                case MotionEvent.ACTION_DOWN: {
                    Display display = getWindowManager().getDefaultDisplay();
                    int screenHeight = display.getHeight();
                    int newNoteViewHeight = mAddNewNote.getHeight();
                    // 计算按钮其实y坐标
                    int start = screenHeight - newNoteViewHeight;
                    // 计算触摸y坐标
                    int eventY = start + (int) event.getY();
                    /**
                     * Minus TitleBar's height
                     */
                    // 因标题栏进行边界修正
                    if (mState == ListEditState.SUB_FOLDER) {
                        eventY -= mTitleBar.getHeight();
                        start -= mTitleBar.getHeight();
                    }
                    /**
                     * HACKME:When click the transparent part of "New Note" button, dispatch
                     * the event to the list view behind this button. The transparent part of
                     * "New Note" button could be expressed by formula y=-0.12x+94（Unit:pixel）
                     * and the line top of the button. The coordinate based on left of the "New
                     * Note" button. The 94 represents maximum height of the transparent part.
                     * Notice that, if the background of the button changes, the formula should
                     * also change. This is very bad, just for the UI designer's strong requirement.
                     */
                    // ui设计师的按钮存在透明部分，当点击透明部分时应将事件转发给下面的列表视图
                    // 按钮边界使用 y=-0.12x+94 描述，属于硬编码，所以按钮改变时应重新计算
                    // 这不是好的实现方式，只是为了满足ui设计师的强烈需求

                    // 点击到了空白区域
                    if (event.getY() < (event.getX() * (-0.12) + 94)) {
                        // 获取列表可见的最后一个有效项目
                        View view = mNotesListView.getChildAt(mNotesListView.getChildCount() - 1
                                - mNotesListView.getFooterViewsCount());
                        // 如果这个条目在透明区域下方
                        if (view != null && view.getBottom() > start
                                && (view.getTop() < (start + 94))) {
                            // 修正坐标
                            mOriginY = (int) event.getY();
                            mDispatchY = eventY;
                            event.setLocation(event.getX(), mDispatchY);
                            // 进入转发模式
                            mDispatch = true;
                            // 将事件分发给这个条目
                            return mNotesListView.dispatchTouchEvent(event);
                        }
                    }
                    break;
                }
                // 移动
                case MotionEvent.ACTION_MOVE: {
                    // 正在手动分发
                    if (mDispatch) {
                        // 修正坐标
                        mDispatchY += (int) event.getY() - mOriginY;
                        event.setLocation(event.getX(), mDispatchY);
                        // 手动分发
                        return mNotesListView.dispatchTouchEvent(event);
                    }
                    break;
                }
                // 抬起或取消
                default: {
                    // 分发模式下
                    if (mDispatch) {
                        // 重置坐标
                        event.setLocation(event.getX(), mDispatchY);
                        // 分发标志置false
                        mDispatch = false;
                        // 手动分发
                        return mNotesListView.dispatchTouchEvent(event);
                    }
                    break;
                }
            }
            return false;
        }

    };

    // 异步查询笔记数据库
    private void startAsyncNotesListQuery() {
        // 根据当前目录选择过滤策略
        String selection = (mCurrentFolderId == Notes.ID_ROOT_FOLDER) ? ROOT_FOLDER_SELECTION
                : NORMAL_SELECTION;
        // 构建查询
        /**
         *    SELECT    _id, alert_date, bg_color_id, created_date, has_attachment, modified_date,
         *              notes_count, parent_id, snippet, type, widget_id, widget_type
         *    FROM      notes
         *    WHERE     [selection]
         *    ORDER BY  type DESC, modified_date DESC;
         */
        mBackgroundQueryHandler.startQuery(FOLDER_NOTE_LIST_QUERY_TOKEN, null,
                Notes.CONTENT_NOTE_URI, NoteItemData.PROJECTION, selection, new String[] {
                    String.valueOf(mCurrentFolderId)
                }, NoteColumns.TYPE + " DESC," + NoteColumns.MODIFIED_DATE + " DESC");
    }

    private final class BackgroundQueryHandler extends AsyncQueryHandler {
        public BackgroundQueryHandler(ContentResolver contentResolver) {
            super(contentResolver);
        }

        // 利用令牌识别查询请求
        @Override
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
            switch (token) {
                // 查询文件夹下所有笔记
                case FOLDER_NOTE_LIST_QUERY_TOKEN:
                    mNotesListAdapter.changeCursor(cursor);
                    break;
                // 查询所有文件夹
                case FOLDER_LIST_QUERY_TOKEN:
                    if (cursor != null && cursor.getCount() > 0) {
                        showFolderListMenu(cursor);
                    } else {
                        Log.e(TAG, "Query folder failed");
                    }
                    break;
                default:
                    return;
            }
        }
    }

    // 弹出文件夹列表
    private void showFolderListMenu(Cursor cursor) {
        // 弹出文件选择的对话框
        AlertDialog.Builder builder = new AlertDialog.Builder(NotesListActivity.this);
        builder.setTitle(R.string.menu_title_select_folder);
        final FoldersListAdapter adapter = new FoldersListAdapter(this, cursor);
        builder.setAdapter(adapter, new DialogInterface.OnClickListener() {

            public void onClick(DialogInterface dialog, int which) {
                // 批量移动
                DataUtils.batchMoveToFolder(mContentResolver,
                        mNotesListAdapter.getSelectedItemIds(), adapter.getItemId(which));
                // 弹出提示框
                Toast.makeText(
                        NotesListActivity.this,
                        getString(R.string.format_move_notes_to_folder,
                                mNotesListAdapter.getSelectedCount(),
                                adapter.getFolderName(NotesListActivity.this, which)),
                        Toast.LENGTH_SHORT).show();
                mModeCallBack.finishActionMode();
            }
        });
        builder.show();
    }

    // 创建新笔记
    private void createNewNote() {
        // 创建新任务
        Intent intent = new Intent(this, NoteEditActivity.class);
        // 任务动作是插入
        intent.setAction(Intent.ACTION_INSERT_OR_EDIT);
        // 任务路径是现在的文件夹路径
        intent.putExtra(Notes.INTENT_EXTRA_FOLDER_ID, mCurrentFolderId);
        // 执行并等待结果
        this.startActivityForResult(intent, REQUEST_CODE_NEW_NODE);
    }

    // 批量删除
    private void batchDelete() {
        new AsyncTask<Void, Void, HashSet<AppWidgetAttribute>>() {
            protected HashSet<AppWidgetAttribute> doInBackground(Void... unused) {
                HashSet<AppWidgetAttribute> widgets = mNotesListAdapter.getSelectedWidget();
                // 不是同步模式
                if (!isSyncMode()) {
                    // if not synced, delete notes directly
                    // 直接删除
                    if (DataUtils.batchDeleteNotes(mContentResolver, mNotesListAdapter
                            .getSelectedItemIds())) {
                    } else {
                        Log.e(TAG, "Delete notes error, should not happens");
                    }
                // 同步模式
                } else {
                    // in sync mode, we'll move the deleted note into the trash
                    // folder
                    // 移入垃圾箱文件夹
                    if (!DataUtils.batchMoveToFolder(mContentResolver, mNotesListAdapter
                            .getSelectedItemIds(), Notes.ID_TRASH_FOLER)) {
                        Log.e(TAG, "Move notes to trash folder error, should not happens");
                    }
                }
                return widgets;
            }

            // 完成操作后触发
            @Override
            protected void onPostExecute(HashSet<AppWidgetAttribute> widgets) {
                // 存在桌面组件
                if (widgets != null) {
                    // 遍历桌面组件
                    for (AppWidgetAttribute widget : widgets) {
                        // 检查组件合法
                        if (widget.widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                                && widget.widgetType != Notes.TYPE_WIDGET_INVALIDE) {
                            // 更新组件
                            updateWidget(widget.widgetId, widget.widgetType);
                        }
                    }
                }
                mModeCallBack.finishActionMode();
            }
        }.execute();
    }

    // 删除文件夹
    private void deleteFolder(long folderId) {
        // 根文件夹
        if (folderId == Notes.ID_ROOT_FOLDER) {
            // 不可删除
            Log.e(TAG, "Wrong folder id, should not happen " + folderId);
            return;
        }

        HashSet<Long> ids = new HashSet<Long>();
        ids.add(folderId);
        HashSet<AppWidgetAttribute> widgets = DataUtils.getFolderNoteWidget(mContentResolver,
                folderId);
        if (!isSyncMode()) {
            // if not synced, delete folder directly
            // 不是同步模式，直接删除文件夹
            DataUtils.batchDeleteNotes(mContentResolver, ids);
        } else {
            // in sync mode, we'll move the deleted folder into the trash folder
            // 同步模式，移入垃圾桶文件夹
            DataUtils.batchMoveToFolder(mContentResolver, ids, Notes.ID_TRASH_FOLER);
        }
        // 更新桌面控件
        if (widgets != null) {
            for (AppWidgetAttribute widget : widgets) {
                if (widget.widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                        && widget.widgetType != Notes.TYPE_WIDGET_INVALIDE) {
                    updateWidget(widget.widgetId, widget.widgetType);
                }
            }
        }
    }

    // 打开已有笔记
    private void openNode(NoteItemData data) {
        // 进入编辑页任务
        Intent intent = new Intent(this, NoteEditActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra(Intent.EXTRA_UID, data.getId());
        this.startActivityForResult(intent, REQUEST_CODE_OPEN_NODE);
    }

    // 打开文件夹
    private void openFolder(NoteItemData data) {
        // 更新当前文件夹
        mCurrentFolderId = data.getId();
        // 异步搜索文件夹中的笔记
        startAsyncNotesListQuery();
        // 如果当前文件夹是通话记录文件夹
        if (data.getId() == Notes.ID_CALL_RECORD_FOLDER) {
            // 切换文件夹状态标记
            mState = ListEditState.CALL_RECORD_FOLDER;
            // 隐藏新建按钮
            mAddNewNote.setVisibility(View.GONE);
        // 是普通文件夹
        } else {
            // 切换文件夹状态标记
            mState = ListEditState.SUB_FOLDER;
        }
        // 如果是通话记录文件夹，显示通话记录为标题
        if (data.getId() == Notes.ID_CALL_RECORD_FOLDER) {
            mTitleBar.setText(R.string.call_record_folder_name);
        // 普通文件夹显示本身的标题
        } else {
            mTitleBar.setText(data.getSnippet());
        }
        // 显示标题
        mTitleBar.setVisibility(View.VISIBLE);
    }

    // 点击事件
    public void onClick(View v) {
        switch (v.getId()) {
            // 点击到新建按钮则新建笔记
            case R.id.btn_new_note:
                createNewNote();
                break;
            default:
                break;
        }
    }

    // 显示软键盘
    private void showSoftInput() {
        InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null) {
            inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
        }
    }

    // 隐藏软键盘
    private void hideSoftInput(View view) {
        InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        inputMethodManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    // 显示新建对话框/修改文件夹对话框
    private void showCreateOrModifyFolderDialog(final boolean create) {
        // 构建对话框
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_text, null);
        final EditText etName = (EditText) view.findViewById(R.id.et_foler_name);
        showSoftInput();
        // 修改
        if (!create) {
            if (mFocusNoteDataItem != null) {
                // 文本内容为文件夹内容，标题为重命名
                etName.setText(mFocusNoteDataItem.getSnippet());
                builder.setTitle(getString(R.string.menu_folder_change_name));
            } else {
                Log.e(TAG, "The long click data item is null");
                return;
            }
        // 新建
        } else {
            // 文本内容为空，标题是新建文件夹
            etName.setText("");
            builder.setTitle(this.getString(R.string.menu_create_folder));
        }

        // 添加确认按钮和取消按钮
        builder.setPositiveButton(android.R.string.ok, null);
        builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                hideSoftInput(etName);
            }
        });

        // 展示对话框
        final Dialog dialog = builder.setView(view).show();
        final Button positive = (Button)dialog.findViewById(android.R.id.button1);
        // 设置确认按钮的监听器
        positive.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                // 隐藏软键盘
                hideSoftInput(etName);
                // 获取文件夹名字
                String name = etName.getText().toString();
                // 存在相同名字的文件夹，不能重名需要修改
                if (DataUtils.checkVisibleFolderName(mContentResolver, name)) {
                    // 弹出提示框
                    Toast.makeText(NotesListActivity.this, getString(R.string.folder_exist, name),
                            Toast.LENGTH_LONG).show();
                    // 全选文字
                    etName.setSelection(0, etName.length());
                    // 直接返回不关闭对话框
                    return;
                }
                // 编辑已有文件夹
                if (!create) {
                    if (!TextUtils.isEmpty(name)) {
                        // 编辑新数据行
                        ContentValues values = new ContentValues();
                        values.put(NoteColumns.SNIPPET, name);
                        values.put(NoteColumns.TYPE, Notes.TYPE_FOLDER);
                        values.put(NoteColumns.LOCAL_MODIFIED, 1);
                        // 数据库UPDATE语句
                        mContentResolver.update(Notes.CONTENT_NOTE_URI, values, NoteColumns.ID
                                + "=?", new String[] {
                            String.valueOf(mFocusNoteDataItem.getId())
                        });
                    }
                // 新建文件夹
                } else if (!TextUtils.isEmpty(name)) {
                    // 编辑新数据行
                    ContentValues values = new ContentValues();
                    values.put(NoteColumns.SNIPPET, name);
                    values.put(NoteColumns.TYPE, Notes.TYPE_FOLDER);
                    // 数据库INSERT语句
                    mContentResolver.insert(Notes.CONTENT_NOTE_URI, values);
                }
                // 关闭对话框
                dialog.dismiss();
            }
        });

        // 文件夹名是空白时，禁用确认按钮
        if (TextUtils.isEmpty(etName.getText())) {
            positive.setEnabled(false);
        }
        /**
         * When the name edit text is null, disable the positive button
         */
        // 绑定文本变化时的监听器
        etName.addTextChangedListener(new TextWatcher() {
            // 变化前触发
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // TODO Auto-generated method stub

            }

            // 变化时触发
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (TextUtils.isEmpty(etName.getText())) {
                    positive.setEnabled(false);
                } else {
                    positive.setEnabled(true);
                }
            }

            // 变化后触发
            public void afterTextChanged(Editable s) {
                // TODO Auto-generated method stub

            }
        });
    }

    // 返回键触发
    @Override
    public void onBackPressed() {
        switch (mState) {
            // 子文件夹
            case SUB_FOLDER:
                // 进入父文件夹
                mCurrentFolderId = Notes.ID_ROOT_FOLDER;
                // 修改文件状态
                mState = ListEditState.NOTE_LIST;
                // 查询笔记列表
                startAsyncNotesListQuery();
                // 隐藏标题界面
                mTitleBar.setVisibility(View.GONE);
                break;
            // 通话记录文件夹
            case CALL_RECORD_FOLDER:
                // 进入根目录
                mCurrentFolderId = Notes.ID_ROOT_FOLDER;
                mState = ListEditState.NOTE_LIST;
                // 显示新建按钮
                mAddNewNote.setVisibility(View.VISIBLE);
                mTitleBar.setVisibility(View.GONE);
                startAsyncNotesListQuery();
                break;
            // 在主列表返回
            case NOTE_LIST:
                // 调用上层处理方法
                super.onBackPressed();
                break;
            default:
                break;
        }
    }

    // 更新桌面组件
    private void updateWidget(int appWidgetId, int appWidgetType) {
        // 创建更新桌面组件任务
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        // 任务中加入组件型号参数
        if (appWidgetType == Notes.TYPE_WIDGET_2X) {
            intent.setClass(this, NoteWidgetProvider_2x.class);
        } else if (appWidgetType == Notes.TYPE_WIDGET_4X) {
            intent.setClass(this, NoteWidgetProvider_4x.class);
        } else {
            Log.e(TAG, "Unspported widget type");
            return;
        }

        // 加入组件id参数
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[] {
            appWidgetId
        });

        // 广播
        sendBroadcast(intent);
        setResult(RESULT_OK, intent);
    }

    // 长按菜单创建时触发
    private final OnCreateContextMenuListener mFolderOnCreateContextMenuListener = new OnCreateContextMenuListener() {
        public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
            if (mFocusNoteDataItem != null) {
                // 设置标题
                menu.setHeaderTitle(mFocusNoteDataItem.getSnippet());
                // 设置菜单项
                menu.add(0, MENU_FOLDER_VIEW, 0, R.string.menu_folder_view);
                menu.add(0, MENU_FOLDER_DELETE, 0, R.string.menu_folder_delete);
                menu.add(0, MENU_FOLDER_CHANGE_NAME, 0, R.string.menu_folder_change_name);
            }
        }
    };

    // 长按菜单关闭时触发
    @Override
    public void onContextMenuClosed(Menu menu) {
        if (mNotesListView != null) {
            mNotesListView.setOnCreateContextMenuListener(null);
        }
        super.onContextMenuClosed(menu);
    }

    // 选中菜单项时触发
    @Override
    public boolean onContextItemSelected(MenuItem item) {
        if (mFocusNoteDataItem == null) {
            Log.e(TAG, "The long click data item is null");
            return false;
        }
        switch (item.getItemId()) {
            // 查看
            case MENU_FOLDER_VIEW:
                // 打开文件夹
                openFolder(mFocusNoteDataItem);
                break;
            // 删除
            case MENU_FOLDER_DELETE:
                // 构建提示框
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle(getString(R.string.alert_title_delete));
                builder.setIcon(android.R.drawable.ic_dialog_alert);
                builder.setMessage(getString(R.string.alert_message_delete_folder));
                // 确认按钮和取消按钮
                builder.setPositiveButton(android.R.string.ok,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                deleteFolder(mFocusNoteDataItem.getId());
                            }
                        });
                builder.setNegativeButton(android.R.string.cancel, null);
                // 弹出提示框
                builder.show();
                break;
            // 重命名
            case MENU_FOLDER_CHANGE_NAME:
                showCreateOrModifyFolderDialog(false);
                break;
            default:
                break;
        }

        return true;
    }

    // 显示菜单时触发
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.clear();
        // 加载主菜单
        if (mState == ListEditState.NOTE_LIST) {
            getMenuInflater().inflate(R.menu.note_list, menu);
            // set sync or sync_cancel
            // 正在同步时显示取消同步，没有同步时显示同步
            menu.findItem(R.id.menu_sync).setTitle(
                    GTaskSyncService.isSyncing() ? R.string.menu_sync_cancel : R.string.menu_sync);
        // 加载子文件夹
        } else if (mState == ListEditState.SUB_FOLDER) {
            getMenuInflater().inflate(R.menu.sub_folder, menu);
        // 加载通话笔记文件夹
        } else if (mState == ListEditState.CALL_RECORD_FOLDER) {
            getMenuInflater().inflate(R.menu.call_record_folder, menu);
        } else {
            Log.e(TAG, "Wrong state:" + mState);
        }
        return true;
    }

    // 选择菜单项时触发
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            // 新建文件夹
            case R.id.menu_new_folder: {
                showCreateOrModifyFolderDialog(true);
                break;
            }
            //导出文本
            case R.id.menu_export_text: {
                exportNoteToText();
                break;
            }
            // 同步
            case R.id.menu_sync: {
                // 已开启同步模式
                if (isSyncMode()) {
                    // 是开始同步菜单，则进行同步
                    if (TextUtils.equals(item.getTitle(), getString(R.string.menu_sync))) {
                        GTaskSyncService.startSync(this);
                    // 是停止同步菜单，则停止同步
                    } else {
                        GTaskSyncService.cancelSync(this);
                    }
                // 未开启同步模式
                } else {
                    //跳转设置
                    startPreferenceActivity();
                }
                break;
            }
            // 设置
            case R.id.menu_setting: {
                startPreferenceActivity();
                break;
            }
            // 新建笔记
            case R.id.menu_new_note: {
                createNewNote();
                break;
            }
            // 搜索
            case R.id.menu_search:
                onSearchRequested();
                break;
            default:
                break;
        }
        return true;
    }

    // 搜索请求时触发
    @Override
    public boolean onSearchRequested() {
        startSearch(null, false, null /* appData */, false);
        return true;
    }

    // 导出笔记
    private void exportNoteToText() {
        final BackupUtils backup = BackupUtils.getInstance(NotesListActivity.this);
        new AsyncTask<Void, Void, Integer>() {

            // 后台进程
            @Override
            protected Integer doInBackground(Void... unused) {
                return backup.exportToText();
            }

            // ui进程
            @Override
            protected void onPostExecute(Integer result) {
                // SD卡未挂载
                if (result == BackupUtils.STATE_SD_CARD_UNMOUONTED) {
                    // 弹出对话框
                    AlertDialog.Builder builder = new AlertDialog.Builder(NotesListActivity.this);
                    builder.setTitle(NotesListActivity.this
                            .getString(R.string.failed_sdcard_export));
                    builder.setMessage(NotesListActivity.this
                            .getString(R.string.error_sdcard_unmounted));
                    builder.setPositiveButton(android.R.string.ok, null);
                    builder.show();
                // 成功
                } else if (result == BackupUtils.STATE_SUCCESS) {
                    // 弹出对话框
                    AlertDialog.Builder builder = new AlertDialog.Builder(NotesListActivity.this);
                    builder.setTitle(NotesListActivity.this
                            .getString(R.string.success_sdcard_export));
                    builder.setMessage(NotesListActivity.this.getString(
                            R.string.format_exported_file_location, backup
                                    .getExportedTextFileName(), backup.getExportedTextFileDir()));
                    builder.setPositiveButton(android.R.string.ok, null);
                    builder.show();
                // 运行时异常
                } else if (result == BackupUtils.STATE_SYSTEM_ERROR) {
                    // 弹出对话框
                    AlertDialog.Builder builder = new AlertDialog.Builder(NotesListActivity.this);
                    builder.setTitle(NotesListActivity.this
                            .getString(R.string.failed_sdcard_export));
                    builder.setMessage(NotesListActivity.this
                            .getString(R.string.error_sdcard_export));
                    builder.setPositiveButton(android.R.string.ok, null);
                    builder.show();
                }
            }

        }.execute();
    }

    // 是否为同步模式
    private boolean isSyncMode() {
        return NotesPreferenceActivity.getSyncAccountName(this).trim().length() > 0;
    }

    //跳转到设置界面
    private void startPreferenceActivity() {
        Activity from = getParent() != null ? getParent() : this;
        Intent intent = new Intent(from, NotesPreferenceActivity.class);
        from.startActivityIfNeeded(intent, -1);
    }

    // 列表项点击监听器
    private class OnListItemClickListener implements OnItemClickListener {

        //点击事件
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            if (view instanceof NotesListItem) {
                NoteItemData item = ((NotesListItem) view).getItemData();
                // 选择模式中
                if (mNotesListAdapter.isInChoiceMode()) {
                    // 选中笔记
                    if (item.getType() == Notes.TYPE_NOTE) {
                        // 修正位置
                        position = position - mNotesListView.getHeaderViewsCount();
                        // 反转选择框状态
                        mModeCallBack.onItemCheckedStateChanged(null, position, id,
                                !mNotesListAdapter.isSelectedItem(position));
                    }
                    return;
                }

                switch (mState) {
                    // 主菜单
                    case NOTE_LIST:
                        if (item.getType() == Notes.TYPE_FOLDER
                                || item.getType() == Notes.TYPE_SYSTEM) {
                            openFolder(item);
                        } else if (item.getType() == Notes.TYPE_NOTE) {
                            openNode(item);
                        } else {
                            Log.e(TAG, "Wrong note type in NOTE_LIST");
                        }
                        break;
                    // 子文件夹和通话笔记文件夹
                    case SUB_FOLDER:
                    case CALL_RECORD_FOLDER:
                        if (item.getType() == Notes.TYPE_NOTE) {
                            openNode(item);
                        } else {
                            Log.e(TAG, "Wrong note type in SUB_FOLDER");
                        }
                        break;
                    default:
                        break;
                }
            }
        }

    }

    // 查找目标文件夹
    private void startQueryDestinationFolders() {
        // 构建查询条件
        // 主菜单：   type = ? AND parent_id <> ? AND id <> ?
        // 其他：     ( type = ? AND parent_id <> ? AND id <> ? ) OR ( id = 0 )
        String selection = NoteColumns.TYPE + "=? AND " + NoteColumns.PARENT_ID + "<>? AND " + NoteColumns.ID + "<>?";
        selection = (mState == ListEditState.NOTE_LIST) ? selection:
            "(" + selection + ") OR (" + NoteColumns.ID + "=" + Notes.ID_ROOT_FOLDER + ")";

        // 查询，懒得写sql了凑活看看吧
        mBackgroundQueryHandler.startQuery(FOLDER_LIST_QUERY_TOKEN,
                null,
                Notes.CONTENT_NOTE_URI,
                FoldersListAdapter.PROJECTION,
                selection,
                new String[] {
                        String.valueOf(Notes.TYPE_FOLDER),
                        String.valueOf(Notes.ID_TRASH_FOLER),
                        String.valueOf(mCurrentFolderId)
                },
                NoteColumns.MODIFIED_DATE + " DESC");
    }

    // 长按触发
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        if (view instanceof NotesListItem) {
            mFocusNoteDataItem = ((NotesListItem) view).getItemData();
            // 根目录下，不在选择模式中
            if (mFocusNoteDataItem.getType() == Notes.TYPE_NOTE && !mNotesListAdapter.isInChoiceMode()) {
                // 开启多选栏
                if (mNotesListView.startActionMode(mModeCallBack) != null) {
                    // 勾选长按的项
                    mModeCallBack.onItemCheckedStateChanged(null, position, id, true);
                    // 震动
                    mNotesListView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                } else {
                    Log.e(TAG, "startActionMode fails");
                }
            // 当前在文件夹内
            } else if (mFocusNoteDataItem.getType() == Notes.TYPE_FOLDER) {
                // 弹出菜单
                mNotesListView.setOnCreateContextMenuListener(mFolderOnCreateContextMenuListener);
            }
        }
        return false;
    }
}
