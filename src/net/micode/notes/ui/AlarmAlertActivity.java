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
 * 闹钟提醒ui
 */
package net.micode.notes.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.DialogInterface.OnDismissListener;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Window;
import android.view.WindowManager;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.tool.DataUtils;

import java.io.IOException;


public class AlarmAlertActivity extends Activity implements OnClickListener, OnDismissListener {
    // 笔记的id
    private long mNoteId;
    // 笔记内容
    private String mSnippet;
    // 最大预览长度
    private static final int SNIPPET_PREW_MAX_LEN = 60;
    // 媒体播放器
    MediaPlayer mPlayer;

    // 创建闹钟事件
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 关闭标题栏显示
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        final Window win = getWindow();
        // 添加锁屏显示标志位
        win.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);

        // 屏幕不亮时，强制点亮，保持常亮，允许亮屏锁定
        if (!isScreenOn()) {
            win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR);
        }

        // 获得intent
        Intent intent = getIntent();

        try {
            // 获取笔记id
            mNoteId = Long.valueOf(intent.getData().getPathSegments().get(1));
            // 获取笔记内容
            mSnippet = DataUtils.getSnippetById(this.getContentResolver(), mNoteId);
            // 对笔记内容进行处理，当长度超过预览最大长度时，阶段并在后面增加省略号
            mSnippet = mSnippet.length() > SNIPPET_PREW_MAX_LEN ? mSnippet.substring(0,
                    SNIPPET_PREW_MAX_LEN) + getResources().getString(R.string.notelist_string_info)
                    : mSnippet;
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            return;
        }

        // 实例化媒体播放器对象，
        mPlayer = new MediaPlayer();
        // 确认钟对应的笔记可见（用户创建并且未删除）
        if (DataUtils.visibleInNoteDatabase(getContentResolver(), mNoteId, Notes.TYPE_NOTE)) {
            showActionDialog();
            playAlarmSound();
        } else {
            finish();
        }
    }

    // 判断屏幕是否亮起
    private boolean isScreenOn() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm.isScreenOn();
    }


    // 音频播放
    private void playAlarmSound() {
        // 获取闹钟铃声资源路径
        Uri url = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);

        // 判断是否受静音模式影响
        // 从系统中获取掩码，其记录每种音频流是否受影响
        int silentModeStreams = Settings.System.getInt(getContentResolver(),
                Settings.System.MODE_RINGER_STREAMS_AFFECTED, 0);

        // 通过掩码与当前闹钟音频流进行位运算，判断是否受影响
        if ((silentModeStreams & (1 << AudioManager.STREAM_ALARM)) != 0) {
            // 受到影响，音频流类型设置为静音模式音频流流
            mPlayer.setAudioStreamType(silentModeStreams);
        } else {
            // 不受影响，设置为闹钟音频流
            mPlayer.setAudioStreamType(AudioManager.STREAM_ALARM);
        }
        // 配置并启动媒体播放器
        try {
            // 设置音频资源uri
            mPlayer.setDataSource(this, url);
            // 播放器准备
            mPlayer.prepare();
            // 循环播放
            mPlayer.setLooping(true);
            // 开始播放
            mPlayer.start();
        } catch (IllegalArgumentException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (SecurityException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IllegalStateException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    // 弹出闹钟交互对话框
    private void showActionDialog() {
        // 创建对话框
        AlertDialog.Builder dialog = new AlertDialog.Builder(this);
        // 设置标题，展示便签信息，设置确认按钮
        dialog.setTitle(R.string.app_name);
        dialog.setMessage(mSnippet);
        dialog.setPositiveButton(R.string.notealert_ok, this);
        // 屏幕亮起时添加进入笔记按钮
        if (isScreenOn()) {
            dialog.setNegativeButton(R.string.notealert_enter, this);
        }
        // 展示对话框，当用户点击任何按钮时对话框消失
        dialog.show().setOnDismissListener(this);
    }

    // 设置按钮点击后的动作
    public void onClick(DialogInterface dialog, int which) {
        switch (which) {
            // 进入笔记按钮
            case DialogInterface.BUTTON_NEGATIVE:
                // 创建intent，目标是笔记编辑界面
                Intent intent = new Intent(this, NoteEditActivity.class);
                // 动作设为打开界面
                intent.setAction(Intent.ACTION_VIEW);
                // 传入笔记id
                intent.putExtra(Intent.EXTRA_UID, mNoteId);
                // 跳转
                startActivity(intent);
                break;
            // 确认按钮
            default:
                // 无事发生，直接退出
                break;
        }
    }

    // 对话框消失执行的方法
    public void onDismiss(DialogInterface dialog) {
        // 停止播放闹钟音频
        stopAlarmSound();
        finish();
    }

    // 停止闹钟播放音频
    private void stopAlarmSound() {
        if (mPlayer != null) {
            mPlayer.stop();
            mPlayer.release();
            mPlayer = null;
        }
    }
}
