package com.danielwkh.weime

/** 簡易拼音詞庫。格式：每行「拼音 字/詞 字/詞 ...」。ü 用 v 輸入（nv、lv）。可自行擴充。 */
object Dict {
    private val map: Map<String, List<String>> by lazy {
        val m = LinkedHashMap<String, MutableList<String>>()
        for (line in RAW.lines()) {
            val p = line.trim().split(' ')
            if (p.size > 1) m.getOrPut(p[0]) { mutableListOf() }.addAll(p.drop(1))
        }
        m
    }

    fun lookup(p: String): List<Pair<String, Int>> {
        if (p.isEmpty()) return emptyList()
        val out = LinkedHashSet<Pair<String, Int>>()
        map[p]?.sortedByDescending { CjEngine.freqOf(it) }?.forEach { out.add(it to p.length) }
        map.keys.filter { it.length > p.length && it.startsWith(p) }
            .sortedBy { it.length }
            .take(15)
            .forEach { k ->
                map[k]!!.sortedByDescending { CjEngine.freqOf(it) }.take(3).forEach { out.add(it to p.length) }
            }
        if (out.isEmpty()) {
            for (n in p.length - 1 downTo 1) {
                val c = map[p.substring(0, n)]
                if (c != null) {
                    c.sortedByDescending { CjEngine.freqOf(it) }.forEach { out.add(it to n) }
                    break
                }
            }
        }
        if (!CjEngine.hasFreq()) return out.take(40)
        // 常用字優先：有字頻（或是詞組）的排前面，沒有字頻的罕見字排後面
        val (common, rare) = out.toList().partition { it.first.length > 1 || CjEngine.freqOf(it.first) > 0L }
        return (common + rare).take(40)
    }

    private const val RAW = """
nihao 你好
women 我們
tamen 他們
nimen 你們
zhongguo 中國
xiexie 謝謝
zaijian 再見
duibuqi 對不起
meiguanxi 沒關係
shenme 什麼
zenme 怎麼
weishenme 為什麼
keyi 可以
xianzai 現在
jintian 今天
mingtian 明天
zuotian 昨天
xihuan 喜歡
pengyou 朋友
gongzuo 工作
xuexiao 學校
xianggang 香港
taiwan 台灣
dianhua 電話
shouji 手機
diannao 電腦
wangluo 網絡
mima 密碼
haode 好的
zhidao 知道
bushi 不是
shijian 時間
shenghuo 生活
pingguo 蘋果
tiaoma 條碼
saomiao 掃描
shuru 輸入
a 啊 阿
ai 愛 哀 艾
an 安 按 案 暗
ang 昂
ao 奧 熬
ba 把 八 吧 爸 巴
bai 白 百 擺 敗
ban 半 班 辦 般 板
bang 幫 棒
bao 包 保 報 寶 抱
bei 北 被 背 杯 備
ben 本 奔
bi 比 必 筆 避 閉
bian 變 邊 便 遍
biao 表 標
bie 別
bing 並 病 冰 兵
bo 波 播 博
bu 不 部 步 布 補
ca 擦
cai 才 菜 采 財 猜
can 參 餐 殘
cang 藏 倉
cao 草 操
ce 冊 測 策
cha 查 差 茶 察
chan 產
chang 長 場 常 唱 廠 嘗
chao 超 吵 朝
che 車
chen 陳 沉 晨
cheng 成 城 程 稱 誠 乘
chi 吃 遲 持 池
chong 重 衝 充 蟲
chou 抽 醜
chu 出 初 處 除 楚 廚
chuan 傳 穿 船
chuang 窗 創 床
chui 吹
chun 春 純
ci 次 此 詞 刺
cong 從 聰
cu 粗
cuo 錯
da 大 打 達 答
dai 帶 代 待 袋
dan 但 單 擔 蛋 淡
dang 當 黨
dao 到 道 倒 導 刀 島
de 的 得 德 地
deng 等 燈 登
di 地 第 低 底 敵
dian 電 點 店 典
diao 調 掉
die 跌
ding 定 訂 頂
dong 東 動 懂 冬 洞
dou 都 豆 斗
du 都 讀 度 獨 毒
duan 短 段 斷
dui 對 隊 堆
dun 頓
duo 多 朵 躲
e 額 餓 惡 鵝
en 恩
er 二 而 兒 耳
fa 發 法 髮
fan 反 飯 犯 翻 煩
fang 方 放 房 防 訪
fei 非 飛 費 肥
fen 分 份 粉
feng 風 封 豐 瘋
fo 佛
fou 否
fu 父 服 富 副 復 福 付 夫 婦
gai 改 該 概
gan 乾 趕 敢 感 幹
gang 港 鋼 剛
gao 高 告 搞
ge 個 各 歌 格 哥
gei 給
gen 跟 根
geng 更
gong 工 公 共 功 供 宮
gou 夠 狗 購 溝
gu 古 故 顧 骨 股 鼓
gua 掛 瓜
guai 怪
guan 關 管 觀 官 館 慣
guang 光 廣 逛
gui 貴 規 鬼 歸
guo 國 過 果 鍋
ha 哈
hai 還 海 害 孩
han 漢 寒 喊 含
hang 行 航
hao 好 號 豪
he 和 河 喝 何 合 賀
hei 黑
hen 很 恨
heng 橫
hong 紅 洪 宏
hou 後 厚 候 猴
hu 戶 湖 護 呼 胡 虎 互
hua 話 花 畫 化 華 劃
huai 壞 懷
huan 換 還 歡 環 緩
huang 黃 皇 慌
hui 會 回 灰 惠 匯 揮
hun 混 婚 魂
huo 活 火 或 貨 獲
ji 幾 記 機 及 己 即 集 級 極 急 激 寄 計 擠
jia 家 加 假 價 架 嫁
jian 見 件 間 建 簡 檢 堅 健
jiang 將 講 江 降 獎
jiao 叫 教 交 角 腳 較 覺
jie 結 接 街 姐 解 界 節 借 介
jin 進 近 今 金 緊 盡 僅 禁
jing 經 京 精 驚 景 靜 境 淨
jiu 就 九 久 舊 酒 救
ju 句 局 具 舉 居 巨 據 聚
juan 卷 捲 倦
jue 決 覺 絕
jun 軍 君 均
ka 卡 咖
kai 開
kan 看 刊 砍
kang 康 抗
kao 考 靠 烤
ke 可 科 課 客 刻 克 渴
ken 肯
keng 坑
kong 空 控 恐
kou 口 扣
ku 苦 哭 庫 褲
kua 誇 跨
kuai 快 塊 筷
kuan 寬 款
kuang 況 狂 礦
kui 虧
kun 困
kuo 擴
la 拉 啦 辣
lai 來 賴
lan 藍 懶 爛 蘭
lang 浪 狼 朗
lao 老 勞 牢
le 了 樂
lei 類 累 淚 雷
leng 冷
li 里 力 理 立 利 禮 離 歷 例 李
lian 連 練 臉 聯 戀 蓮
liang 兩 量 亮 涼 糧
liao 了 料 聊 療
lie 列 烈 裂
lin 林 臨 鄰
ling 零 領 令 靈 另 鈴
liu 六 流 留 劉 柳
long 龍 隆
lou 樓 漏
lu 路 陸 錄 露 魯 盧
lv 綠 旅 律 慮
luan 亂
lun 論 輪
luo 落 羅 洛
ma 嗎 媽 馬 麻 罵
mai 買 賣 麥 邁
man 滿 慢 漫
mang 忙
mao 毛 貓 帽 貿
me 麼
mei 沒 每 美 妹 眉 梅 媒
men 們 門 悶
meng 夢 猛 蒙 盟
mi 米 密 迷 秘
mian 面 麵 免 棉
miao 秒 妙 苗 廟
min 民 敏
ming 明 名 命 鳴
mo 沒 摸 末 默 模 魔
mou 某 謀
mu 木 目 母 幕 墓 慕
na 那 拿 哪 納 娜
nai 奶 耐
nan 難 南 男
nao 腦 鬧 惱
ne 呢
nei 內
neng 能
ni 你 呢 泥 逆 尼
nian 年 念 唸
niao 鳥 尿
nin 您
ning 寧
niu 牛 扭 紐
nong 農 濃 弄
nu 努 怒 奴
nv 女
o 哦
ou 歐 偶
pa 怕 爬
pai 排 牌 拍 派
pan 判 盤 盼
pang 旁 胖
pao 跑 炮 泡
pei 陪 配 培 賠
pen 盆 噴
peng 朋 碰 棚
pi 批 皮 屁 疲
pian 片 便 篇 騙 偏
piao 票 漂
pin 品 貧 拼
ping 平 瓶 評 蘋 憑
po 破 婆 迫 坡
pu 普 鋪 撲 葡
qi 起 氣 七 期 奇 其 企 齊 棋 欺
qia 恰
qian 前 錢 千 淺 牽 簽
qiang 強 牆 搶
qiao 橋 巧 敲
qie 切 且
qin 親 秦 勤 琴
qing 請 情 清 青 輕 慶 晴
qiong 窮
qiu 求 球 秋
qu 去 區 取 曲 趣 屈
quan 全 權 圈 勸 泉
que 卻 確 缺
qun 群 裙
ran 然 染
rang 讓
rao 繞 擾
re 熱
ren 人 認 任 仁 忍
reng 仍
ri 日
rong 容 榮 融
rou 肉 柔
ru 如 入 乳
ruan 軟
run 潤
ruo 若 弱
sa 撒
sai 賽 塞
san 三 散 傘
sang 桑 喪
sao 掃 騷
se 色
sen 森
sha 沙 殺 傻 啥
shai 曬
shan 山 善 閃 衫
shang 上 商 傷 尚
shao 少 燒 紹
she 社 設 舌 蛇 射 捨
shei 誰
shen 什 身 深 神 甚 審
sheng 生 聲 省 勝 升 剩 聖
shi 是 時 事 十 市 使 師 實 式 世 室 試 失 視 始 石 食 史
shou 手 收 受 首 守 售 壽
shu 書 數 樹 屬 熟 輸 術 鼠 叔
shua 刷 耍
shuai 帥 摔
shuang 雙 爽 霜
shui 水 睡 稅
shun 順 瞬
shuo 說 碩
si 四 死 思 司 私 似 絲 寺
song 送 松 宋 頌
sou 搜
su 素 速 訴 蘇 俗
suan 算 酸
sui 歲 隨 雖 碎
sun 孫 損
suo 所 縮 鎖 索
ta 他 她 它 塔 踏
tai 太 台 態 抬 臺
tan 談 彈 探 炭 嘆
tang 湯 糖 堂 躺 唐
tao 套 逃 桃 討 陶
te 特
teng 疼 騰
ti 提 題 體 替 踢
tian 天 田 填 甜 添
tiao 條 跳 挑
tie 鐵 貼
ting 聽 停 庭 挺 廳
tong 同 通 痛 統 童 桶
tou 頭 投 偷 透
tu 圖 土 突 途 吐
tuan 團
tui 退 推 腿
tun 吞
tuo 脫 拖 託
wa 挖 娃 瓦
wai 外 歪
wan 完 晚 玩 萬 灣 碗 彎
wang 王 往 忘 網 望 旺
wei 為 位 未 委 微 衛 味 圍 偉 威 維
wen 問 文 溫 聞 穩 紋
weng 翁
wo 我 握 臥
wu 五 無 物 務 午 武 舞 屋 霧 誤
xi 西 習 洗 喜 系 希 細 戲 席 息 析
xia 下 夏 嚇 蝦 俠
xian 先 現 線 縣 顯 限 鮮 險 閒 憲
xiang 想 向 像 相 香 響 項 鄉 箱 象
xiao 小 笑 校 消 效 曉 孝
xie 寫 些 謝 鞋 協 血 邪
xin 新 心 信 星 辛 欣 薪
xing 行 星 姓 性 形 興 型 醒 幸
xiong 兄 凶 胸 雄 熊
xiu 修 休 秀 袖 繡
xu 需 許 序 續 虛 須 緒
xuan 選 懸 宣 旋
xue 學 雪 血 穴
xun 訓 尋 迅 巡
ya 呀 壓 牙 亞 鴨
yan 眼 言 研 顏 演 煙 嚴 驗 沿 鹽 宴
yang 樣 陽 羊 養 楊 洋 仰
yao 要 藥 搖 腰 咬 邀 遙
ye 也 夜 業 葉 爺 野 頁
yi 一 以 已 意 義 易 醫 億 衣 依 藝 議 益 異 移
yin 因 音 銀 引 飲 印 隱
ying 應 英 營 影 硬 迎 贏
yo 喲
yong 用 永 勇 擁 湧
you 有 又 友 由 油 右 遊 優 憂 郵
yu 於 與 語 魚 雨 預 玉 育 遇 欲 域 愈 餘
yuan 元 原 園 遠 院 願 員 圓 源 緣
yue 月 越 約 樂 閱 躍
yun 雲 運 允 孕 暈
za 雜 砸
zai 在 再 載 災
zan 咱 暫 贊
zang 髒 藏
zao 早 造 遭 糟 燥
ze 則 責 擇
zei 賊
zen 怎
zeng 增 曾 贈
zha 炸 扎 渣 閘
zhai 窄 摘 宅 債
zhan 站 戰 展 佔 沾
zhang 長 張 章 漲 掌 丈 帳
zhao 找 照 招 著 趙 召
zhe 這 著 者 折 哲
zhei 這
zhen 真 鎮 振 針 陣 珍 診
zheng 正 整 政 證 爭 鄭 睜
zhi 之 知 只 直 紙 至 指 制 治 志 質 值 職 止
zhong 中 種 重 眾 終 鐘 忠
zhou 週 周 州 洲 舟 皺
zhu 主 住 注 助 祝 築 豬 竹 煮 朱
zhua 抓
zhuan 轉 專 賺 傳
zhuang 裝 壯 狀 撞
zhui 追 墜
zhun 準
zhuo 桌 捉
zi 自 子 字 資 紫 姿
zong 總 宗 綜 縱
zou 走 奏
zu 組 足 租 族 祖
zuan 鑽
zui 最 嘴 罪 醉
zun 尊 遵
zuo 做 作 坐 左 座 昨
"""
}
