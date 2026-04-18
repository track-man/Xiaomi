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
 * 设置界面
 */
package net.micode.notes.ui;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.ActionBar;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.gtask.remote.GTaskSyncService;


public class NotesPreferenceActivity extends PreferenceActivity {
    // 设置界面文件名
    public static final String PREFERENCE_NAME = "notes_preferences";

    // 谷歌邮箱
    public static final String PREFERENCE_SYNC_ACCOUNT_NAME = "pref_key_account_name";

    // 最后一次同步的时间
    public static final String PREFERENCE_LAST_SYNC_TIME = "pref_last_sync_time";

    // 随机背景
    public static final String PREFERENCE_SET_BG_COLOR_KEY = "pref_key_bg_random_appear";

    // ui组件id
    private static final String PREFERENCE_SYNC_ACCOUNT_KEY = "pref_sync_account_key";

    // 用户使用的是合法的谷歌邮箱
    private static final String AUTHORITIES_FILTER_KEY = "authorities";

    // 同步账号区域组件
    private PreferenceCategory mAccountCategory;

    // 消息接收器
    private GTaskReceiver mReceiver;

    // 谷歌账号列表缓存
    private Account[] mOriAccounts;

    // 是否刚点击添加账号按钮
    private boolean mHasAddedAccount;

    // 创建时触发
    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        /* using the app icon for navigation */
        // 使用应用图标进行导航
        getActionBar().setDisplayHomeAsUpEnabled(true);

        // 从资源中加载设置布局
        addPreferencesFromResource(R.xml.preferences);
        mAccountCategory = (PreferenceCategory) findPreference(PREFERENCE_SYNC_ACCOUNT_KEY);
        // 初始化
        mReceiver = new GTaskReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(GTaskSyncService.GTASK_SERVICE_BROADCAST_NAME);
        registerReceiver(mReceiver, filter);

        mOriAccounts = null;
        //添加一个头部视图
        View header = LayoutInflater.from(this).inflate(R.layout.settings_header, null);
        getListView().addHeaderView(header, null, true);
    }

    // 返回应用时触发
    @Override
    protected void onResume() {
        super.onResume();

        // need to set sync account automatically if user has added a new
        // account
        // 当用户添加了一个新的账号时应自动添加这个同步账号
        if (mHasAddedAccount) {
            Account[] accounts = getGoogleAccounts();
            // 用户点击了添加账号按钮，并且现有的账号比原有的账号多时
            if (mOriAccounts != null && accounts.length > mOriAccounts.length) {
                // 遍历新账号列表，如果旧列表中没找到则found置false
                for (Account accountNew : accounts) {
                    boolean found = false;
                    for (Account accountOld : mOriAccounts) {
                        if (TextUtils.equals(accountOld.name, accountNew.name)) {
                            found = true;
                            break;
                        }
                    }
                    // 如果是新账号，则添加同步账户
                    if (!found) {
                        setSyncAccount(accountNew.name);
                        break;
                    }
                }
            }
        }

        refreshUI();
    }

    // 清理资源
    @Override
    protected void onDestroy() {
        if (mReceiver != null) {
            unregisterReceiver(mReceiver);
        }
        super.onDestroy();
    }

    // 加载账户设置
    private void loadAccountPreference() {
        // 清除现有设置
        mAccountCategory.removeAll();

        // 获取一个新的用户设置对象，配置默认同步账户与ui界面
        Preference accountPref = new Preference(this);
        final String defaultAccount = getSyncAccountName(this);
        accountPref.setTitle(getString(R.string.preferences_account_title));
        accountPref.setSummary(getString(R.string.preferences_account_summary));
        // 绑定监听器
        accountPref.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            public boolean onPreferenceClick(Preference preference) {
                // 当前没有正在同步中
                if (!GTaskSyncService.isSyncing()) {
                    // 没有账号
                    if (TextUtils.isEmpty(defaultAccount)) {
                        // the first time to set account
                        // 首次设置账号弹出对话框
                        showSelectAccountAlertDialog();
                    // 有账号
                    } else {
                        // if the account has already been set, we need to promp
                        // user about the risk
                        // 弹出确认风险对话框
                        showChangeAccountConfirmAlertDialog();
                    }
                // 同步中
                } else {
                    // 弹出提示框，正在同步时无法切换账户
                    Toast.makeText(NotesPreferenceActivity.this,
                            R.string.preferences_toast_cannot_change_account, Toast.LENGTH_SHORT)
                            .show();
                }
                return true;
            }
        });

        mAccountCategory.addPreference(accountPref);
    }

    // 加载同步按钮
    private void loadSyncButton() {
        // 加载控件
        Button syncButton = (Button) findViewById(R.id.preference_sync_button);
        TextView lastSyncTimeView = (TextView) findViewById(R.id.prefenerece_sync_status_textview);

        // set button state
        // 设置按钮状态
        // 如果正在同步中
        if (GTaskSyncService.isSyncing()) {
            // 设置取消同步按钮
            syncButton.setText(getString(R.string.preferences_button_sync_cancel));
            syncButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    GTaskSyncService.cancelSync(NotesPreferenceActivity.this);
                }
            });
        // 设置立即同步按钮
        } else {
            syncButton.setText(getString(R.string.preferences_button_sync_immediately));
            syncButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    GTaskSyncService.startSync(NotesPreferenceActivity.this);
                }
            });
        }
        // 有同步账户时按钮设为可用，无同步账户时按钮设为不可用
        syncButton.setEnabled(!TextUtils.isEmpty(getSyncAccountName(this)));

        // set last sync time
        // 记录上次同步时间
        // 如果当前正在同步
        if (GTaskSyncService.isSyncing()) {
            // 展示同步进度字符串
            lastSyncTimeView.setText(GTaskSyncService.getProgressString());
            lastSyncTimeView.setVisibility(View.VISIBLE);
        // 当前未同步
        } else {
            long lastSyncTime = getLastSyncTime(this);
            // 如果存在上次同步时间
            if (lastSyncTime != 0) {
                // 显示上次同步时间
                lastSyncTimeView.setText(getString(R.string.preferences_last_sync_time,
                        DateFormat.format(getString(R.string.preferences_last_sync_time_format),
                                lastSyncTime)));
                lastSyncTimeView.setVisibility(View.VISIBLE);
            } else {
                lastSyncTimeView.setVisibility(View.GONE);
            }
        }
    }

    // 刷新ui
    private void refreshUI() {
        // 加载当前账户设置
        loadAccountPreference();
        // 加载同步按钮
        loadSyncButton();
    }

    // 弹出选择账户对话框
    private void showSelectAccountAlertDialog() {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this);

        // 对话框标题
        View titleView = LayoutInflater.from(this).inflate(R.layout.account_dialog_title, null);
        TextView titleTextView = (TextView) titleView.findViewById(R.id.account_dialog_title);
        titleTextView.setText(getString(R.string.preferences_dialog_select_account_title));
        // 对话框内容
        TextView subtitleTextView = (TextView) titleView.findViewById(R.id.account_dialog_subtitle);
        subtitleTextView.setText(getString(R.string.preferences_dialog_select_account_tips));

        dialogBuilder.setCustomTitle(titleView);
        // 设置确认按钮
        dialogBuilder.setPositiveButton(null, null);

        // 获取同步账号
        Account[] accounts = getGoogleAccounts();
        String defAccount = getSyncAccountName(this);

        mOriAccounts = accounts;
        // 是否刚点击添加账号按钮标志位置false
        mHasAddedAccount = false;

        if (accounts.length > 0) {
            // 获取账户列表
            CharSequence[] items = new CharSequence[accounts.length];
            final CharSequence[] itemMapping = items;
            // 寻找当前账户的索引
            int checkedItem = -1;
            int index = 0;
            for (Account account : accounts) {
                if (TextUtils.equals(account.name, defAccount)) {
                    checkedItem = index;
                }
                items[index++] = account.name;
            }
            // 构建单选列表
            dialogBuilder.setSingleChoiceItems(items, checkedItem,
                    new DialogInterface.OnClickListener() {
                        // 点击事件
                        public void onClick(DialogInterface dialog, int which) {
                            // 设置同步账户为点击的单选项
                            setSyncAccount(itemMapping[which].toString());
                            // 关闭对话框，刷新ui
                            dialog.dismiss();
                            refreshUI();
                        }
                    });
        }

        // 布局填充
        View addAccountView = LayoutInflater.from(this).inflate(R.layout.add_account_text, null);
        dialogBuilder.setView(addAccountView);

        // 提示对话框
        final AlertDialog dialog = dialogBuilder.show();
        addAccountView.setOnClickListener(new View.OnClickListener() {
            // 点击事件
            public void onClick(View v) {
                // 刚点击添加账号按钮，标志位置true
                mHasAddedAccount = true;
                // 设置添加账号任务，等待返回结果，关闭对话框
                Intent intent = new Intent("android.settings.ADD_ACCOUNT_SETTINGS");
                intent.putExtra(AUTHORITIES_FILTER_KEY, new String[] {
                    "gmail-ls"
                });
                startActivityForResult(intent, -1);
                dialog.dismiss();
            }
        });
    }

    // 弹出修改账户确认对话框
    private void showChangeAccountConfirmAlertDialog() {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this);

        // 加载标题和内容
        View titleView = LayoutInflater.from(this).inflate(R.layout.account_dialog_title, null);
        TextView titleTextView = (TextView) titleView.findViewById(R.id.account_dialog_title);
        titleTextView.setText(getString(R.string.preferences_dialog_change_account_title,
                getSyncAccountName(this)));
        TextView subtitleTextView = (TextView) titleView.findViewById(R.id.account_dialog_subtitle);
        subtitleTextView.setText(getString(R.string.preferences_dialog_change_account_warn_msg));
        dialogBuilder.setCustomTitle(titleView);

        // 三个按钮文本
        CharSequence[] menuItemArray = new CharSequence[] {
                getString(R.string.preferences_menu_change_account),
                getString(R.string.preferences_menu_remove_account),
                getString(R.string.preferences_menu_cancel)
        };
        // 设置按钮
        dialogBuilder.setItems(menuItemArray, new DialogInterface.OnClickListener() {
            // 为按钮绑定点击事件
            public void onClick(DialogInterface dialog, int which) {
                // 点击修改同步账户
                if (which == 0) {
                    // 弹出选择账户对话框
                    showSelectAccountAlertDialog();
                // 点击移除同步账户
                } else if (which == 1) {
                    // 移除账户
                    removeSyncAccount();
                    refreshUI();
                }
                // 点击取消
                //无事发生
            }
        });
        dialogBuilder.show();
    }

    // 获取谷歌账户
    private Account[] getGoogleAccounts() {
        AccountManager accountManager = AccountManager.get(this);
        return accountManager.getAccountsByType("com.google");
    }

    // 设置同步账户
    private void setSyncAccount(String account) {
        // 当前同步账户和传入的账户不同
        if (!getSyncAccountName(this).equals(account)) {
            // 新账号写入
            SharedPreferences settings = getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = settings.edit();
            if (account != null) {
                // 传入用户名
                editor.putString(PREFERENCE_SYNC_ACCOUNT_NAME, account);
            } else {
                editor.putString(PREFERENCE_SYNC_ACCOUNT_NAME, "");
            }
            editor.commit();

            // clean up last sync time
            // 清空上次同步时间
            setLastSyncTime(this, 0);

            // clean up local gtask related info
            // 清理本地与云端相关的信息
            new Thread(new Runnable() {
                public void run() {
                    ContentValues values = new ContentValues();
                    // 清空笔记的云端id和同步状态id
                    values.put(NoteColumns.GTASK_ID, "");
                    values.put(NoteColumns.SYNC_ID, 0);
                    // UPDATE语句
                    getContentResolver().update(Notes.CONTENT_NOTE_URI, values, null, null);
                }
            }).start();

            // 弹出对话框
            Toast.makeText(NotesPreferenceActivity.this,
                    getString(R.string.preferences_toast_success_set_accout, account),
                    Toast.LENGTH_SHORT).show();
        }
    }

    // 移除同步账户
    private void removeSyncAccount() {
        // 获取用户设置
        SharedPreferences settings = getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = settings.edit();
        // 删除设置中的账户名和上次同步时间
        if (settings.contains(PREFERENCE_SYNC_ACCOUNT_NAME)) {
            editor.remove(PREFERENCE_SYNC_ACCOUNT_NAME);
        }
        if (settings.contains(PREFERENCE_LAST_SYNC_TIME)) {
            editor.remove(PREFERENCE_LAST_SYNC_TIME);
        }
        editor.commit();

        // clean up local gtask related info
        // 清理本地与云端相关的信息
        new Thread(new Runnable() {
            public void run() {
                ContentValues values = new ContentValues();
                values.put(NoteColumns.GTASK_ID, "");
                values.put(NoteColumns.SYNC_ID, 0);
                // UPDATE语句，同上
                getContentResolver().update(Notes.CONTENT_NOTE_URI, values, null, null);
            }
        }).start();
    }

    // 获取同步账户名
    public static String getSyncAccountName(Context context) {
        SharedPreferences settings = context.getSharedPreferences(PREFERENCE_NAME,
                Context.MODE_PRIVATE);
        return settings.getString(PREFERENCE_SYNC_ACCOUNT_NAME, "");
    }

    // 设置上次同步时间
    public static void setLastSyncTime(Context context, long time) {
        SharedPreferences settings = context.getSharedPreferences(PREFERENCE_NAME,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = settings.edit();
        editor.putLong(PREFERENCE_LAST_SYNC_TIME, time);
        editor.commit();
    }

    // 获取上次同步时间
    public static long getLastSyncTime(Context context) {
        SharedPreferences settings = context.getSharedPreferences(PREFERENCE_NAME,
                Context.MODE_PRIVATE);
        return settings.getLong(PREFERENCE_LAST_SYNC_TIME, 0);
    }

    // 监听后台广播
    private class GTaskReceiver extends BroadcastReceiver {

        // 应用发出广播时执行
        @Override
        public void onReceive(Context context, Intent intent) {
            // 刷新ui
            refreshUI();
            // 如果当前正在在同步
            if (intent.getBooleanExtra(GTaskSyncService.GTASK_SERVICE_BROADCAST_IS_SYNCING, false)) {
                // 输出同步进度字符串
                TextView syncStatus = (TextView) findViewById(R.id.prefenerece_sync_status_textview);
                syncStatus.setText(intent
                        .getStringExtra(GTaskSyncService.GTASK_SERVICE_BROADCAST_PROGRESS_MSG));
            }

        }
    }

    // 导航栏按钮被选中时触发
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            // Home键
            case android.R.id.home:
                // 跳转回主界面
                Intent intent = new Intent(this, NotesListActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
                return true;
            default:
                return false;
        }
    }
}
