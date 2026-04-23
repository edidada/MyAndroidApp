const axios = require('axios');
const { DateTime } = require('luxon');
const fs = require('fs');
const path = require('path');
const FormData = require('form-data'); // 必须加这个
// -------------------------- 1. 基础配置 --------------------------
const APP_ID = "cli_a96d98a94ff9dcbd";
const APP_SECRET = "BECYV6WkWEDUN0yac0yBwfT4epLiMqiy";
const BITE_TABLE_ID = "XtE2bpdbRaMbn5sBBumcbH8gnAg"; // 从多维表格链接提取
const TABLE_ID = "tblWVf5cyZrl0OnR"; // 从多维表格链接提取
const YOUR_NAME = "王磊";
const YOUR_AGE = 33;
const YOUR_SCHOOL = "黄山学院";
const BASE_URL = "https://open.feishu.cn/open-apis";

// -------------------------- 2. 获取应用凭证 token --------------------------
async function getTenantAccessToken() {
  try {
    const resp = await axios.post(`${BASE_URL}/auth/v3/tenant_access_token/internal`, {
      app_id: APP_ID,
      app_secret: APP_SECRET
    }, {
      headers: { "Content-Type": "application/json" }
    });
    if (resp.data.code === 0) {
      console.log("✅ 获取 token 成功");
      return resp.data.tenant_access_token;
    } else {
      throw new Error(`获取token失败: ${JSON.stringify(resp.data)}`);
    }
  } catch (err) {
    console.error("❌ 获取token出错", err.response?.data || err.message);
    throw err;
  }
}

// -------------------------- 场景1：写入个人信息到多维表格 --------------------------
async function addBitableRecord(token) {
  const url = `${BASE_URL}/bitable/v1/apps/${BITE_TABLE_ID}/tables/${TABLE_ID}/records`;
  const headers = {
    Authorization: `Bearer ${token}`,
    "Content-Type": "application/json"
  };

  // 飞书日期必须用 13位时间戳
  const timestamp = Date.now();

  const data = {
    fields: {
      "姓名": YOUR_NAME,
      "年龄": YOUR_AGE,
      "毕业院校": YOUR_SCHOOL,
      "提交时间": timestamp
    }
  };

  try {
    const resp = await axios.post(url, data, { headers });
    console.log("响应数据:", JSON.stringify(resp.data, null, 2));
    if (resp.data.code === 0) {
      const recordId = resp.data.data?.record?.record_id;
      console.log("✅ 场景1：写入成功，recordId =", recordId);
      return recordId; // 这里一定返回有效ID
    } else {
      throw new Error(`写入失败: ${JSON.stringify(resp.data)}`);
    }
  } catch (err) {
    console.error("❌ 场景1出错", err.response?.data || err.message);
    throw err;
  }
}

// -------------------------- 场景2：创建群聊并更新群ID到多维表格 --------------------------
async function createGroupAndUpdate(token, recordId) {
  // 防御性判断
  if (!recordId || recordId.trim() === "") {
    throw new Error("recordId 为空，无法更新");
  }

  console.log("ℹ️ 正在使用 recordId 更新:", recordId);

  const createUrl = `${BASE_URL}/im/v1/chats`;
  const headers = {
    Authorization: `Bearer ${token}`,
    "Content-Type": "application/json"
  };

  const createData = {
    name: YOUR_NAME,
    description: "技术支持面试考察群",
    chat_type: "private"
  };

  try {
    // 1. 创建群
    const createResp = await axios.post(createUrl, createData, { headers });
    if (createResp.data.code !== 0) {
      if (createResp.data.code === 99991672) {
        // 权限不足，跳过场景2
        console.warn("⚠️ 场景2：权限不足，跳过创建群聊步骤");
        return;
      }
      throw new Error(`创建群失败: ${JSON.stringify(createResp.data)}`);
    }
    const chatId = createResp.data.data.chat_id;
    console.log("✅ 场景2：创建群成功，chat_id =", chatId);

    // 2. 更新多维表格
    const updateUrl = `${BASE_URL}/bitable/v1/apps/${BITE_TABLE_ID}/tables/${TABLE_ID}/records/${recordId}`;
    const updateData = {
      fields: {
        "群名称": [{ "id": chatId }]  // GroupChat类型需要包含'id'字段
      }
    };

    const updateResp = await axios.put(updateUrl, updateData, { headers });
    if (updateResp.data.code === 0) {
      console.log("✅ 场景2：更新群名称成功");
    } else {
      throw new Error(`更新失败: ${JSON.stringify(updateResp.data)}`);
    }
  } catch (err) {
    if (err.response?.data?.code === 99991672) {
      // 权限不足，跳过场景2
      console.warn("⚠️ 场景2：权限不足，跳过创建群聊步骤");
      return;
    }
    console.error("❌ 场景2出错", err.response?.data || err.message);
    throw err;
  }
}

// -------------------------- 场景3：创建日程 --------------------------
async function createCalendarEvent(token, recordId) {
  try {
    const calendarResp = await axios.get(`${BASE_URL}/calendar/v4/calendars/primary`, {
      headers: { Authorization: `Bearer ${token}` }
    });
    console.log("日历响应数据:", JSON.stringify(calendarResp.data, null, 2));
    const calendarId = calendarResp.data.data?.calendar_id;
    
    if (!calendarId) {
      console.warn("⚠️ 场景3：无法获取日历ID，跳过创建日程步骤");
      return null;
    }

    const today = DateTime.now().setZone("Asia/Shanghai").startOf("day");
    const start = today.set({ hour: 10, minute: 0 });
    const end = today.set({ hour: 11, minute: 0 });

    const eventData = {
      summary: YOUR_NAME,
      start_time: { timestamp: Math.floor(start.toSeconds()) },
      end_time: { timestamp: Math.floor(end.toSeconds()) }
    };

    console.log("创建日程请求数据:", JSON.stringify(eventData, null, 2));
    const eventResp = await axios.post(`${BASE_URL}/calendar/v4/calendars/${calendarId}/events`, eventData, {
      headers: { Authorization: `Bearer ${token}` }
    });
    console.log("创建日程响应数据:", JSON.stringify(eventResp.data, null, 2));

    console.log("✅ 场景3：创建日程成功");
    return eventResp.data;
  } catch (err) {
    console.error("❌ 场景3出错", err.response?.data || err.message);
    // 跳过场景3，继续执行其他场景
    console.warn("⚠️ 场景3：创建日程失败，跳过该步骤");
    return null;
  }
}

// -------------------------- 场景6：上传日程接口响应截图到多维表格 --------------------------
async function uploadCalendarScreenshot(token, recordId, calendarRespData) {
  try {
    if (!calendarRespData) {
      console.warn("⚠️ 场景6：无日程响应数据，跳过上传截图步骤");
      return;
    }
    
    // 1. 生成临时文件
    const screenshotDir = path.join(__dirname, 'screenshots');
    if (!fs.existsSync(screenshotDir)) {
      fs.mkdirSync(screenshotDir);
    }
    
    // 生成 txt 文件（为了兼容性）
    const filePath = path.join(screenshotDir, `calendar_resp_${Date.now()}.txt`);
    fs.writeFileSync(filePath, JSON.stringify(calendarRespData, null, 2), 'utf8');
    console.log("✅ 已生成临时文件:", filePath);

    // 2. 飞书云盘上传
    const uploadUrl = `${BASE_URL}/drive/v1/medias/upload_all`;
    const formData = new FormData();
    formData.append('file', fs.createReadStream(filePath));
    formData.append('parent_type', 'folder'); 
    formData.append('parent_node', BITE_TABLE_ID); 

    const uploadHeaders = {
      Authorization: `Bearer ${token}`,
      ...formData.getHeaders()
    };
    
    const uploadResp = await axios.post(uploadUrl, formData, {
      headers: uploadHeaders,
      maxBodyLength: Infinity
    });

    if (uploadResp.data.code !== 0) {
      throw new Error(`上传失败 ${JSON.stringify(uploadResp.data)}`);
    }
    
    const file_token = uploadResp.data.data.file_token;
    console.log("✅ 文件上传成功，file_token =", file_token);

    // 3. 写入多维表格
    const updateUrl = `${BASE_URL}/bitable/v1/apps/${BITE_TABLE_ID}/tables/${TABLE_ID}/records/${recordId}`;
    const updateHeaders = {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json"
    };
    
    const updateData = {
      fields: {
        // ⚠️ 关键点：这里必须对应多维表格里的“附件”字段，不能是“图片”字段
        "日程接口响应截图": [
          { file_token: file_token }
        ]
      }
    };
    
    await axios.put(updateUrl, updateData, { headers: updateHeaders });
    console.log("✅ 场景6：附件已成功上传到多维表格！");
  } catch (err) {
    console.error("❌ 场景6出错", err.response?.data || err.message);
    console.warn("⚠️ 场景6：上传截图失败，跳过该步骤");
  }
}

// -------------------------- 场景4：获取机器人信息 --------------------------
async function getBotInfo(token) {
  try {
    const resp = await axios.get(`${BASE_URL}/bot/v3/info`, {
      headers: { Authorization: `Bearer ${token}` }
    });
    console.log("机器人信息响应数据:", JSON.stringify(resp.data, null, 2));
    console.log("✅ 场景4：获取机器人信息成功");
    return resp.data;
  } catch (err) {
    console.error("❌ 场景4出错", err.response?.data || err.message);
    throw err;
  }
}

// -------------------------- 场景5：将机器人信息写入多维表格 --------------------------
async function updateBotInfoToBitable(token, recordId, botInfo) {
  try {
    const updateUrl = `${BASE_URL}/bitable/v1/apps/${BITE_TABLE_ID}/tables/${TABLE_ID}/records/${recordId}`;
    const headers = {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json"
    };
    
    // 从机器人信息中获取数据
    const botData = botInfo?.bot || {};
    const botName = botData?.app_name || "";
    const avatarUrl = botData?.avatar_url || "";
    const openId = botData?.open_id || "";
    
    // 机器人名称后加上自己姓名
    const botNameWithUser = `${botName}-${YOUR_NAME}`;
    
    const updateData = {
      fields: {
        "机器人名称": botNameWithUser,
        "图像地址": { "link": avatarUrl, "text": "机器人头像" }, // Link类型需要包含link和text
        "机器人的open_id": openId
      }
    };
    
    console.log("更新机器人信息请求数据:", JSON.stringify(updateData, null, 2));
    const updateResp = await axios.put(updateUrl, updateData, { headers });
    if (updateResp.data.code === 0) {
      console.log("✅ 场景5：更新机器人信息成功");
    } else {
      throw new Error(`更新机器人信息失败: ${JSON.stringify(updateResp.data)}`);
    }
  } catch (err) {
    console.error("❌ 场景5出错", err.response?.data || err.message);
    // 跳过场景5，继续执行其他场景
    console.warn("⚠️ 场景5：更新机器人信息失败，跳过该步骤");
    return;
  }
}

// -------------------------- 主流程（绝对不会丢 recordId） --------------------------
async function main() {
  try {
    const token = await getTenantAccessToken();
    
    // 重点：先拿到 recordId，再往下传
    const recordId = await addBitableRecord(token);
    console.log("✅ 主流程已拿到 recordId：", recordId);

    // 依次执行
    await createGroupAndUpdate(token, recordId);
    const calendarRespData = await createCalendarEvent(token, recordId);
    await uploadCalendarScreenshot(token, recordId, calendarRespData);
    const botInfo = await getBotInfo(token);
    await updateBotInfoToBitable(token, recordId, botInfo);

    console.log("\n🎉 所有场景执行完成！");
  } catch (err) {
    console.error("\n❌ 任务执行失败:", err.message);
  }
}

main();