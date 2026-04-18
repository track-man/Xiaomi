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
 * 便签文本编辑界面
 */
package net.micode.notes.ui;

import android.content.Context;
import android.graphics.Rect;
import android.text.Layout;
import android.text.Selection;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.URLSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.MotionEvent;
import android.widget.EditText;

import net.micode.notes.R;

import java.util.HashMap;
import java.util.Map;

public class NoteEditText extends EditText {
    private static final String TAG = "NoteEditText";
    // 位置索引
    private int mIndex;
    // 删除操作前光标所在位置
    private int mSelectionStartBeforeDelete;

    // 交互协议常量
    private static final String SCHEME_TEL = "tel:" ;
    private static final String SCHEME_HTTP = "http:" ;
    private static final String SCHEME_EMAIL = "mailto:" ;

    // 交互协议常量与交互协议字符串的静态映射
    private static final Map<String, Integer> sSchemaActionResMap = new HashMap<String, Integer>();
    static {
        sSchemaActionResMap.put(SCHEME_TEL, R.string.note_link_tel);
        sSchemaActionResMap.put(SCHEME_HTTP, R.string.note_link_web);
        sSchemaActionResMap.put(SCHEME_EMAIL, R.string.note_link_email);
    }

    /**
     * Call by the {@link NoteEditActivity} to delete or add edit text
     */
    // 监听器
    public interface OnTextViewChangeListener {
        /**
         * Delete current edit text when {@link KeyEvent#KEYCODE_DEL} happens
         * and the text is null
         */
        // 文本为空时按下退格键触发
        void onEditTextDelete(int index, String text);

        /**
         * Add edit text after current edit text when {@link KeyEvent#KEYCODE_ENTER}
         * happen
         */
        // 按下回车键触发
        void onEditTextEnter(int index, String text);

        /**
         * Hide or show item option when text change
         */
        // 文本变化时触发
        void onTextChange(int index, boolean hasText);
    }

    private OnTextViewChangeListener mOnTextViewChangeListener;

    // 构造器
    public NoteEditText(Context context) {
        super(context, null);
        mIndex = 0;
    }

    // 设置索引
    public void setIndex(int index) {
        mIndex = index;
    }

    // 设置监听器
    public void setOnTextViewChangeListener(OnTextViewChangeListener listener) {
        mOnTextViewChangeListener = listener;
    }

    // 构造器
    public NoteEditText(Context context, AttributeSet attrs) {
        super(context, attrs, android.R.attr.editTextStyle);
    }

    // 构造器
    public NoteEditText(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        // TODO Auto-generated constructor stub
    }

    // 触摸时触发
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            // 按下时
            case MotionEvent.ACTION_DOWN:

                // 获取坐标
                int x = (int) event.getX();
                int y = (int) event.getY();
                // 扣除内边距
                x -= getTotalPaddingLeft();
                y -= getTotalPaddingTop();
                // 移动文字
                x += getScrollX();
                y += getScrollY();

                Layout layout = getLayout();
                // 光标应该在的行
                int line = layout.getLineForVertical(y);
                // 光标应该在的字符
                int off = layout.getOffsetForHorizontal(line, x);
                Selection.setSelection(getText(), off);
                break;
        }

        return super.onTouchEvent(event);
    }

    // 按键按下时触发
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            // 回车
            case KeyEvent.KEYCODE_ENTER:
                // 拦截回车
                if (mOnTextViewChangeListener != null) {
                    return false;
                }
                break;
            // 删除
            case KeyEvent.KEYCODE_DEL:
                // 记录删除位置
                mSelectionStartBeforeDelete = getSelectionStart();
                break;
            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    // 按键抬起时触发
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch(keyCode) {
            // 删除
            case KeyEvent.KEYCODE_DEL:
                if (mOnTextViewChangeListener != null) {
                    // 光标在行首，且不在开头
                    if (0 == mSelectionStartBeforeDelete && mIndex != 0) {
                        // 调用下层方法继续处理
                        mOnTextViewChangeListener.onEditTextDelete(mIndex, getText().toString());
                        return true;
                    }
                } else {
                    Log.d(TAG, "OnTextViewChangeListener was not seted");
                }
                break;
            // 回车
            case KeyEvent.KEYCODE_ENTER:
                if (mOnTextViewChangeListener != null) {
                    // 获取光标位置
                    int selectionStart = getSelectionStart();
                    // 获取光标后的文本
                    String text = getText().subSequence(selectionStart, length()).toString();
                    // 文本框设置为光标前的文本
                    setText(getText().subSequence(0, selectionStart));
                    // 产生文本框，内容是光标后的文本
                    mOnTextViewChangeListener.onEditTextEnter(mIndex + 1, text);
                } else {
                    Log.d(TAG, "OnTextViewChangeListener was not seted");
                }
                break;
            default:
                break;
        }
        return super.onKeyUp(keyCode, event);
    }

    // 焦点变化
    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        if (mOnTextViewChangeListener != null) {
            // 失去焦点，并且内容是空的
            if (!focused && TextUtils.isEmpty(getText())) {
                //
                mOnTextViewChangeListener.onTextChange(mIndex, false);
            // 其他情况
            } else {
                mOnTextViewChangeListener.onTextChange(mIndex, true);
            }
        }
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
    }

    // 处理选中的超链接文本
    @Override
    protected void onCreateContextMenu(ContextMenu menu) {
        // 文本转化为富文本
        if (getText() instanceof Spanned) {
            // 获取选中区域的开头和结尾
            int selStart = getSelectionStart();
            int selEnd = getSelectionEnd();

            // 排除选取方向影响
            int min = Math.min(selStart, selEnd);
            int max = Math.max(selStart, selEnd);

            // 寻找超链接
            final URLSpan[] urls = ((Spanned) getText()).getSpans(min, max, URLSpan.class);
            // 选中位置包括了超链接
            if (urls.length == 1) {
                // 匹配已有超链接协议
                int defaultResId = 0;
                for(String schema: sSchemaActionResMap.keySet()) {
                    if(urls[0].getURL().indexOf(schema) >= 0) {
                        // 匹配到则使用已有的文本
                        defaultResId = sSchemaActionResMap.get(schema);
                        break;
                    }
                }

                // 没匹配到，返回默认文本
                if (defaultResId == 0) {
                    defaultResId = R.string.note_link_other;
                }

                // 在菜单内增加一行，内容是打开文本
                menu.add(0, 0, 0, defaultResId).setOnMenuItemClickListener(
                        new OnMenuItemClickListener() {
                            public boolean onMenuItemClick(MenuItem item) {
                                // goto a new intent
                                urls[0].onClick(NoteEditText.this);
                                return true;
                            }
                        });
            }
        }
        super.onCreateContextMenu(menu);
    }
}
