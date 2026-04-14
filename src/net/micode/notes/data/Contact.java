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

package net.micode.notes.data;

import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Data;
import android.telephony.PhoneNumberUtils;
import android.util.Log;

import java.util.HashMap;

/**
 * Contact 联系人查询工具类
 * 
 * 功能说明：
 * 本类主要用于根据电话号码查询设备通讯录中的联系人信息。
 * 在便签应用中，当需要显示或关联联系人时，可以通过本类将电话号码转换为联系人姓名。
 * 
 * 缓存机制说明：
 * 为了提高查询效率和减少对系统数据库的压力，本类采用了 HashMap 缓存机制：
 * 1. 使用静态的 sContactCache 存储已查询过的 (电话号码 -> 联系人姓名) 映射
 * 2. 首次查询某个电话号码时，会查询系统通讯录数据库，然后将结果存入缓存
 * 3. 后续查询相同电话号码时，直接从缓存中返回结果，避免重复数据库查询
 * 4. 缓存在应用生命周期内持久存在，直到应用进程结束
 * 
 * 性能优化优势：
 * - 减少重复的数据库查询操作
 * - 提升频繁查询相同号码时的响应速度
 * - 降低对 Android ContentResolver 的调用频率
 * 
 * @author MiCode Open Source Community
 */
public class Contact {
    
    /**
     * sContactCache - 联系人缓存容器
     * 
     * 结构：HashMap<电话号码, 联系人姓名>
     * 
     * 作用说明：
     * - Key（键）：电话号码字符串，格式为原始拨号格式（如 "13812345678"）
     * - Value（值）：对应电话号码的联系人显示名称
     * 
     * 使用注意事项：
     * - 此缓存是类级别的静态变量，在整个应用进程生命周期内有效
     * - 缓存不会自动清理，如果联系人信息在外部被修改，缓存中可能返回过期的数据
     * - 对于便签应用场景，由于联系人信息变更频率较低，这种缓存策略是合理的
     */
    private static HashMap<String, String> sContactCache;
    
    /** 日志标签，用于在 Logcat 中过滤本类的日志输出 */
    private static final String TAG = "Contact";

    /**
     * CALLER_ID_SELECTION - 查询联系人时使用的 SQL 筛选条件
     * 
     * 详细说明：
     * 这是一个用于从 Android 通讯录数据库中查询联系人的 SQL WHERE 子句模板。
     * 它通过电话号码匹配查找对应的联系人姓名。
     * 
     * 筛选条件分解：
     * 1. PHONE_NUMBERS_EQUAL(Phone.NUMBER, ?) 
     *    - 使用系统提供的 Phone.NUMBER 字段比较函数
     *    - 第一个参数是数据库中的电话号码字段
     *    - 第二个参数 ? 是通过 prepared statement 传入的要查询的电话号码
     *    - 此函数会自动处理电话号码格式标准化和比较
     * 
     * 2. Data.MIMETYPE = Phone.CONTENT_ITEM_TYPE
     *    - 确保查询的是电话类型的联系人数据
     *    - 过滤掉邮箱、地址等其他类型的联系人数据
     * 
     * 3. Data.RAW_CONTACT_ID IN (SELECT raw_contact_id FROM phone_lookup WHERE min_match = '+')
     *    - 这是一个子查询，用于处理国际电话号码匹配
     *    - phone_lookup 表存储了号码的标准化格式
     *    - min_match = '+' 表示需要匹配国际冠号
     *    - 确保能够正确匹配带国家代码的国际电话号码
     * 
     * 动态替换说明：
     * - 查询时会将 "+" 替换为 PhoneNumberUtils.toCallerIDMinMatch(phoneNumber) 的返回值
     * - toCallerIDMinMatch() 会根据电话号码格式返回合适的最小匹配前缀
     * - 例如：手机号可能需要匹配 "+86" 前缀，固定电话可能需要更多位数
     */
    private static final String CALLER_ID_SELECTION = "PHONE_NUMBERS_EQUAL(" + Phone.NUMBER
    + ",?) AND " + Data.MIMETYPE + "='" + Phone.CONTENT_ITEM_TYPE + "'"
    + " AND " + Data.RAW_CONTACT_ID + " IN "
            + "(SELECT raw_contact_id "
            + " FROM phone_lookup"
            + " WHERE min_match = '+')";

    /**
     * getContact - 根据电话号码获取联系人姓名
     * 
     * 功能说明：
     * 这是本类的核心方法，用于根据传入的电话号码查询对应的联系人显示名称。
     * 方法会首先检查本地缓存，如果缓存命中则直接返回结果；
     * 如果缓存未命中，则查询系统通讯录数据库，并将结果存入缓存以备后用。
     * 
     * 查询流程：
     * 1. 检查缓存是否已初始化（懒加载模式）
     * 2. 在缓存中查找是否已有该电话号码的记录
     * 3. 缓存命中 → 直接返回缓存中的联系人姓名
     * 4. 缓存未命中 → 执行数据库查询
     *    a. 构建带电话号码前缀匹配的查询条件
     *    b. 执行 ContentResolver.query() 查询通讯录数据
     *    c. 如果找到匹配记录，提取联系人姓名
     *    d. 将结果存入缓存并返回
     *    e. 如果未找到匹配，记录日志并返回 null
     * 
     * @param context  Android 上下文对象，用于访问 ContentResolver
     *                  通常传入 Activity 或 Application 的 context
     * @param phoneNumber 要查询的电话号码字符串
     *                     格式要求：支持原始拨号格式
     *                     例如：13812345678, 010-12345678, +86 138 1234 5678
     * @return String 联系人的显示名称（DISPLAY_NAME）
     *         如果找到匹配的联系人，返回联系人姓名
     *         如果未找到或发生错误，返回 null
     * 
     * 使用示例：
     * <pre>
     * // 在 Activity 中获取联系人姓名
     * String phoneNumber = "13812345678";
     * String contactName = Contact.getContact(this, phoneNumber);
     * if (contactName != null) {
     *     Log.d("Demo", "该号码属于：" + contactName);
     * } else {
     *     Log.d("Demo", "未找到联系人");
     * }
     * </pre>
     * 
     * 注意事项：
     * - 需要在 AndroidManifest.xml 中添加联系人读取权限：
     *   <uses-permission android:name="android.permission.READ_CONTACTS"/>
     * - 返回的姓名是联系人设置的显示名称，可能与通讯录中的姓名不完全一致
     * - 如果一个电话号码关联了多个联系人（如同一个公司电话），返回第一个匹配的
     * - 缓存是进程级别的，应用重启后会清空
     */
    public static String getContact(Context context, String phoneNumber) {
        // 第一步：确保缓存容器已初始化
        // 采用懒加载模式，只在首次调用时才创建缓存对象
        // 这样可以避免应用启动时就占用内存
        if(sContactCache == null) {
            sContactCache = new HashMap<String, String>();
        }

        // 第二步：检查缓存中是否已有该电话号码的记录
        // containsKey() 方法的时间复杂度为 O(1)，非常高效
        // 如果命中缓存，直接返回缓存中的联系人姓名，无需查询数据库
        if(sContactCache.containsKey(phoneNumber)) {
            return sContactCache.get(phoneNumber);
        }

        // 第三步：缓存未命中，执行数据库查询
        // 构建查询条件：将占位符 "+" 替换为适合该电话号码的最小匹配前缀
        // PhoneNumberUtils.toCallerIDMinMatch() 会根据号码格式智能判断需要匹配多少位
        String selection = CALLER_ID_SELECTION.replace("+",
                PhoneNumberUtils.toCallerIDMinMatch(phoneNumber));
        
        // 执行 ContentResolver 查询
        // 参数说明：
        // - Data.CONTENT_URI: 通讯录数据表的内容 URI
        // - new String[] { Phone.DISPLAY_NAME }: 要查询的列名数组，这里只取显示名称
        // - selection: 筛选条件，包含电话号码匹配逻辑
        // - new String[] { phoneNumber }: 筛选条件的参数值
        // - null: 不需要排序
        Cursor cursor = context.getContentResolver().query(
                Data.CONTENT_URI,
                new String [] { Phone.DISPLAY_NAME },
                selection,
                new String[] { phoneNumber },
                null);

        // 第四步：处理查询结果
        if (cursor != null && cursor.moveToFirst()) {
            // 成功找到匹配的联系人
            try {
                // 从游标中获取联系人姓名（第一列，索引为 0）
                String name = cursor.getString(0);
                
                // 将查询结果存入缓存
                // 这样下次查询相同号码时可以直接从缓存返回
                // 这是典型的 "空间换时间" 优化策略
                sContactCache.put(phoneNumber, name);
                
                // 返回联系人姓名
                return name;
            } catch (IndexOutOfBoundsException e) {
                // 捕获数组越界异常
                // 这种情况比较少见，可能是数据库结构发生了变化
                Log.e(TAG, " Cursor get string error " + e.toString());
                return null;
            } finally {
                // 重要：必须在 finally 块中关闭游标
                // 确保在发生异常时也能正确释放数据库资源
                // 避免内存泄漏和数据库连接泄漏
                cursor.close();
            }
        } else {
            // 未找到匹配的联系人
            // 可能的原因：
            // 1. 该号码不在通讯录中
            // 2. 号码格式与通讯录中存储的格式差异太大无法匹配
            // 3. 用户未授予联系人读取权限
            
            Log.d(TAG, "No contact matched with number:" + phoneNumber);
            return null;
        }
    }
}
