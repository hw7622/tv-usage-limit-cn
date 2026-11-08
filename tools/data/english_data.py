# -*- coding: utf-8 -*-
"""英语题库种子数据。

单词表按年级分。生成器会用同一张表出三类题：
词义（英→中）、冠词（a/an）、复数（规则在生成器里，不规则的在 IRREGULAR_PLURAL）。

挑词的原则：都是小学教材高频词，且中文意思唯一——
像「light 灯/轻」这种一词多义的不放，否则题目有歧义。
"""

WORDS = {
    2: [
        ("apple", "苹果"), ("banana", "香蕉"), ("orange", "橙子"), ("pear", "梨"),
        ("cat", "猫"), ("dog", "狗"), ("bird", "鸟"), ("fish", "鱼"),
        ("book", "书"), ("pen", "钢笔"), ("pencil", "铅笔"), ("bag", "书包"),
        ("red", "红色"), ("blue", "蓝色"), ("green", "绿色"), ("yellow", "黄色"),
        ("black", "黑色"), ("white", "白色"), ("cake", "蛋糕"), ("milk", "牛奶"),
        ("water", "水"), ("rice", "米饭"), ("egg", "鸡蛋"), ("tea", "茶"),
        ("sun", "太阳"), ("moon", "月亮"), ("star", "星星"), ("tree", "树"),
        ("flower", "花"), ("grass", "草"), ("desk", "课桌"), ("chair", "椅子"),
        ("door", "门"), ("window", "窗户"), ("school", "学校"), ("teacher", "老师"),
        ("student", "学生"), ("friend", "朋友"), ("happy", "高兴的"), ("big", "大的"),
        ("small", "小的"), ("hot", "热的"), ("cold", "冷的"), ("hand", "手"),
        ("foot", "脚"), ("eye", "眼睛"), ("ear", "耳朵"), ("nose", "鼻子"),
        ("mouth", "嘴"), ("mother", "妈妈"), ("father", "爸爸"), ("sister", "姐妹"),
        ("brother", "兄弟"), ("one", "一"), ("two", "二"), ("three", "三"),
        ("four", "四"), ("five", "五"), ("six", "六"), ("ten", "十"),
    ],
    3: [
        ("animal", "动物"), ("tiger", "老虎"), ("monkey", "猴子"), ("panda", "熊猫"),
        ("rabbit", "兔子"), ("elephant", "大象"), ("lion", "狮子"), ("duck", "鸭子"),
        ("horse", "马"), ("sheep", "绵羊"), ("morning", "早上"), ("afternoon", "下午"),
        ("evening", "傍晚"), ("night", "夜晚"), ("today", "今天"), ("tomorrow", "明天"),
        ("yesterday", "昨天"), ("week", "星期"), ("month", "月份"), ("year", "年"),
        ("spring", "春天"), ("summer", "夏天"), ("autumn", "秋天"), ("winter", "冬天"),
        ("rainy", "下雨的"), ("sunny", "晴朗的"), ("cloudy", "多云的"), ("windy", "有风的"),
        ("snowy", "下雪的"), ("jump", "跳"), ("run", "跑"), ("swim", "游泳"),
        ("sing", "唱歌"), ("dance", "跳舞"), ("draw", "画画"), ("read", "阅读"),
        ("write", "写"), ("listen", "听"), ("speak", "说"), ("kitchen", "厨房"),
        ("bedroom", "卧室"), ("bathroom", "浴室"), ("garden", "花园"), ("library", "图书馆"),
        ("playground", "操场"), ("hospital", "医院"), ("park", "公园"), ("supermarket", "超市"),
        ("doctor", "医生"), ("nurse", "护士"), ("farmer", "农民"), ("driver", "司机"),
        ("police", "警察"), ("cook", "厨师"), ("breakfast", "早饭"), ("lunch", "午饭"),
        ("dinner", "晚饭"), ("hungry", "饥饿的"), ("thirsty", "口渴的"), ("tired", "疲倦的"),
    ],
    4: [
        ("subject", "科目"), ("English", "英语"), ("Chinese", "语文"), ("math", "数学"),
        ("science", "科学"), ("art", "美术"), ("music", "音乐"), ("homework", "家庭作业"),
        ("lesson", "课"), ("classroom", "教室"), ("blackboard", "黑板"), ("computer", "电脑"),
        ("dictionary", "字典"), ("notebook", "笔记本"), ("question", "问题"), ("answer", "答案"),
        ("difficult", "困难的"), ("easy", "容易的"), ("interesting", "有趣的"), ("boring", "无聊的"),
        ("important", "重要的"), ("careful", "仔细的"), ("quiet", "安静的"), ("noisy", "吵闹的"),
        ("clean", "干净的"), ("dirty", "脏的"), ("borrow", "借入"), ("return", "归还"),
        ("invite", "邀请"), ("visit", "拜访"), ("travel", "旅行"), ("arrive", "到达"),
        ("leave", "离开"), ("start", "开始"), ("finish", "结束"), ("choose", "选择"),
        ("remember", "记得"), ("forget", "忘记"), ("believe", "相信"), ("decide", "决定"),
        ("practise", "练习"), ("improve", "提高"), ("prepare", "准备"), ("describe", "描述"),
        ("explain", "解释"), ("compare", "比较"), ("protect", "保护"), ("collect", "收集"),
        ("discover", "发现"), ("celebrate", "庆祝"), ("weather", "天气"), ("umbrella", "雨伞"),
        ("ticket", "票"), ("station", "车站"), ("airport", "机场"), ("village", "村庄"),
        ("bridge", "桥"), ("strong", "强壮的"), ("weak", "虚弱的"), ("quick", "快的"),
    ],
    5: [
        ("hobby", "爱好"), ("stamp", "邮票"), ("coin", "硬币"), ("magazine", "杂志"),
        ("newspaper", "报纸"), ("programme", "节目"), ("information", "信息"), ("message", "消息"),
        ("advice", "建议"), ("problem", "难题"), ("reason", "原因"), ("result", "结果"),
        ("purpose", "目的"), ("chance", "机会"), ("mistake", "错误"), ("experience", "经历"),
        ("memory", "记忆"), ("courage", "勇气"), ("patience", "耐心"), ("kindness", "善良"),
        ("ability", "能力"), ("knowledge", "知识"), ("education", "教育"), ("environment", "环境"),
        ("pollution", "污染"), ("energy", "能源"), ("resource", "资源"), ("recycle", "回收"),
        ("waste", "浪费"), ("damage", "破坏"), ("provide", "提供"), ("support", "支持"),
        ("discuss", "讨论"), ("agree", "同意"), ("refuse", "拒绝"), ("accept", "接受"),
        ("suggest", "建议"), ("warn", "警告"), ("promise", "承诺"), ("apologize", "道歉"),
        ("imagine", "想象"), ("realize", "意识到"), ("express", "表达"), ("translate", "翻译"),
        ("encourage", "鼓励"), ("achieve", "实现"), ("succeed", "成功"), ("continue", "继续"),
        ("avoid", "避免"), ("depend", "依靠"), ("prefer", "更喜欢"), ("wonder", "想知道"),
        ("solution", "解决办法"),
    ],
    6: [
        ("ancient", "古代的"), ("modern", "现代的"), ("century", "世纪"), ("culture", "文化"),
        ("tradition", "传统"), ("custom", "习俗"), ("festival", "节日"), ("ancestor", "祖先"),
        ("legend", "传说"), ("history", "历史"), ("hero", "英雄"), ("wisdom", "智慧"),
        ("spirit", "精神"), ("freedom", "自由"), ("peace", "和平"), ("health", "健康"),
        ("safety", "安全"), ("duty", "责任"), ("honour", "荣誉"), ("confident", "自信的"),
        ("curious", "好奇的"), ("generous", "慷慨的"), ("polite", "有礼貌的"), ("honest", "诚实的"),
        ("patient", "有耐心的"), ("humorous", "幽默的"), ("serious", "严肃的"), ("nervous", "紧张的"),
        ("excited", "兴奋的"), ("surprised", "惊讶的"), ("worried", "担心的"), ("proud", "自豪的"),
        ("ashamed", "羞愧的"), ("anxious", "焦虑的"), ("grateful", "感激的"), ("lonely", "孤独的"),
        ("comfortable", "舒适的"), ("dangerous", "危险的"), ("crowded", "拥挤的"), ("peaceful", "宁静的"),
        ("silent", "沉默的"), ("gradual", "逐渐的"), ("sudden", "突然的"), ("obvious", "明显的"),
        ("possible", "可能的"), ("necessary", "必要的"), ("valuable", "有价值的"), ("similar", "相似的"),
        ("different", "不同的"), ("familiar", "熟悉的"), ("popular", "受欢迎的"), ("successful", "成功的"),
        ("responsible", "负责的"), ("creative", "有创造力的"), ("independent", "独立的"), ("expression", "表达方式"),
    ],
}

# 不规则复数：规则算不出来的，必须显式写。
IRREGULAR_PLURAL = {
    "man": "men", "woman": "women", "child": "children", "foot": "feet",
    "tooth": "teeth", "mouse": "mice", "sheep": "sheep", "fish": "fish",
    "knife": "knives", "leaf": "leaves", "wife": "wives", "half": "halves",
    "potato": "potatoes", "tomato": "tomatoes", "hero": "heroes",
}

# 简单问答：问句 → 正确答语。答语要能独立看懂。
DIALOGUES = {
    3: [
        ("How are you?", "I'm fine, thank you."),
        ("What's your name?", "My name is Tom."),
        ("How old are you?", "I'm nine years old."),
        ("What colour is it?", "It's red."),
        ("Do you like apples?", "Yes, I do."),
        ("Can you swim?", "Yes, I can."),
        ("Where is the cat?", "It's under the desk."),
        ("How's the weather today?", "It's sunny."),
        ("What day is it today?", "It's Monday."),
        ("Nice to meet you.", "Nice to meet you, too."),
    ],
    4: [
        ("What time is it?", "It's eight o'clock."),
        ("Where are you from?", "I'm from China."),
        ("What do you want to drink?", "I want some water."),
        ("How many books do you have?", "I have five books."),
        ("What's your favourite subject?", "My favourite subject is maths."),
        ("What are you doing?", "I'm reading a book."),
        ("Whose bag is this?", "It's my bag."),
        ("How much is it?", "It's ten yuan."),
        ("May I come in?", "Yes, please."),
        ("Thank you very much.", "You're welcome."),
    ],
    5: [
        ("What are you going to do tomorrow?", "I'm going to visit my grandparents."),
        ("How do you go to school?", "I go to school by bus."),
        ("What did you do last weekend?", "I cleaned my room."),
        ("How tall are you?", "I'm one hundred and fifty centimetres tall."),
        ("Where did you go on holiday?", "I went to Beijing."),
        ("What's your hobby?", "My hobby is collecting stamps."),
        ("Would you like some tea?", "Yes, please."),
        ("Excuse me, where is the library?", "It's next to the bank."),
        ("What will the weather be like tomorrow?", "It will be rainy."),
        ("Whose turn is it?", "It's my turn."),
    ],
}

# 可数名词：只有这些才能出「复数」和「a / an」题。
# 形容词、动词加了 s 是不存在的词，出成题就是错题。
NOUNS = {
    2: [
        "apple", "banana", "orange", "pear", "cat", "dog", "bird", "fish",
        "book", "pen", "pencil", "bag", "cake", "egg", "desk", "chair",
        "door", "window", "tree", "flower", "school", "teacher", "student",
        "friend", "hand", "foot", "eye", "ear", "nose", "mouth", "mother",
        "father", "sister", "brother", "star",
    ],
    3: [
        "tiger", "monkey", "panda", "rabbit", "elephant", "lion", "duck",
        "horse", "sheep", "garden", "library", "playground", "hospital",
        "park", "supermarket", "doctor", "nurse", "farmer", "driver",
        "kitchen", "bedroom", "bathroom",
    ],
    4: [
        "subject", "lesson", "classroom", "blackboard", "computer",
        "dictionary", "notebook", "question", "answer", "ticket", "station",
        "airport", "village", "bridge", "umbrella",
    ],
    5: [
        "hobby", "stamp", "coin", "magazine", "newspaper", "programme",
        "message", "chance", "mistake",
    ],
}

# 第二轮扩充：主要是名词（名词还能多出「复数」「冠词」两道题，
# 一个词能顶三道），动词、形容词补齐常用的一批。
WORDS_EXTRA = {
    2: [
        ("ball", "球"), ("doll", "洋娃娃"), ("kite", "风筝"), ("car", "小汽车"),
        ("bus", "公共汽车"), ("bike", "自行车"), ("boat", "小船"), ("plane", "飞机"),
        ("train", "火车"), ("cup", "杯子"), ("box", "盒子"), ("key", "钥匙"),
        ("clock", "钟"), ("phone", "电话"), ("bottle", "瓶子"), ("spoon", "勺子"),
        ("fork", "叉子"), ("bowl", "碗"), ("plate", "盘子"), ("cow", "奶牛"),
        ("pig", "猪"), ("hen", "母鸡"), ("bee", "蜜蜂"), ("ant", "蚂蚁"),
        ("frog", "青蛙"), ("snake", "蛇"), ("bear", "熊"), ("bread", "面包"),
        ("juice", "果汁"), ("candy", "糖果"), ("ice", "冰"), ("soup", "汤"),
        ("face", "脸"), ("hair", "头发"), ("arm", "手臂"), ("leg", "腿"),
        ("pink", "粉色"), ("brown", "棕色"), ("purple", "紫色"), ("grey", "灰色"),
        ("tall", "高的"), ("short", "矮的"), ("fat", "胖的"), ("thin", "瘦的"),
        ("young", "年轻的"), ("old", "年老的"), ("long", "长的"), ("new", "新的"),
        ("nice", "好的"), ("sad", "伤心的"), ("eat", "吃"), ("drink", "喝"),
        ("sleep", "睡觉"), ("walk", "走路"), ("look", "看"), ("help", "帮助"),
        ("open", "打开"), ("close", "关上"), ("come", "来"), ("go", "去"),
    ],
    3: [
        ("giraffe", "长颈鹿"), ("zebra", "斑马"), ("camel", "骆驼"), ("wolf", "狼"),
        ("fox", "狐狸"), ("deer", "鹿"), ("goat", "山羊"), ("dolphin", "海豚"),
        ("whale", "鲸"), ("penguin", "企鹅"), ("jacket", "夹克"), ("dress", "连衣裙"),
        ("skirt", "短裙"), ("shirt", "衬衫"), ("sweater", "毛衣"), ("sock", "袜子"),
        ("glove", "手套"), ("scarf", "围巾"), ("mountain", "山"), ("river", "河流"),
        ("lake", "湖"), ("sea", "大海"), ("island", "岛"), ("forest", "森林"),
        ("field", "田野"), ("cloud", "云"), ("rain", "雨"), ("snow", "雪"),
        ("wind", "风"), ("Monday", "星期一"), ("Tuesday", "星期二"), ("Friday", "星期五"),
        ("birthday", "生日"), ("holiday", "假日"), ("weekend", "周末"), ("uncle", "叔叔"),
        ("aunt", "阿姨"), ("cousin", "堂表兄妹"), ("baby", "婴儿"), ("shoe", "鞋"),
        ("question" , "问题"), ("apple" , "苹果"), ("water" , "水"), ("stone", "石头"),
        ("leaf", "树叶"), ("grass" , "草"), ("rice" , "米饭"), ("sand", "沙子"),
    ],
    4: [
        ("story", "故事"), ("picture", "图画"), ("photo", "照片"), ("letter", "信"),
        ("card", "卡片"), ("gift", "礼物"), ("party", "聚会"), ("game", "游戏"),
        ("project", "项目"), ("naughty", "淘气的"), ("clever", "聪明的"), ("lazy", "懒惰的"),
        ("brave", "勇敢的"), ("friendly", "友好的"), ("helpful", "有帮助的"), ("change", "改变"),
        ("carry", "搬运"), ("climb", "攀爬"), ("count", "数"), ("cover", "覆盖"),
        ("cross", "穿过"), ("drop", "掉落"), ("follow", "跟随"), ("guess", "猜"),
        ("hurry", "赶快"), ("join", "加入"), ("laugh", "笑"), ("move", "移动"),
        ("notice", "注意到"), ("offer", "提供"), ("pick", "采摘"), ("push", "推"),
        ("pull", "拉"), ("share", "分享"), ("shout", "喊"), ("touch", "触摸"),
        ("wait", "等待"), ("watch", "观看"), ("worry", "担心"), ("kind", "种类"),
        ("rule", "规则"), ("sign", "标志"), ("corner", "角落"), ("market", "市场"),
        ("holiday", "假期"), ("island", "岛屿"), ("desert", "沙漠"), ("ocean", "海洋"),
    ],
    5: [
        ("activity", "活动"), ("competition", "比赛"), ("concert", "音乐会"), ("exhibition", "展览"),
        ("journey", "旅程"), ("opinion", "意见"), ("plan", "计划"), ("report", "报告"),
        ("speech", "演讲"), ("trip", "旅行"), ("topic", "话题"), ("common", "常见的"),
        ("correct", "正确的"), ("enough", "足够的"), ("famous", "著名的"), ("foreign", "外国的"),
        ("healthy", "健康的"), ("local", "当地的"), ("natural", "自然的"), ("perfect", "完美的"),
        ("private", "私人的"), ("public", "公共的"), ("recent", "最近的"), ("special", "特别的"),
        ("usual", "通常的"), ("wrong", "错误的"), ("attend", "参加"), ("cause", "引起"),
        ("check", "检查"), ("connect", "连接"), ("create", "创造"), ("develop", "发展"),
        ("divide", "分开"), ("enter", "进入"), ("explore", "探索"), ("fail", "失败"),
        ("fill", "装满"), ("fix", "修理"), ("grow", "生长"), ("hide", "躲藏"),
        ("increase", "增加"), ("introduce", "介绍"), ("invent", "发明"), ("manage", "设法完成"),
        ("measure", "测量"), ("mention", "提到"), ("prevent", "防止"), ("produce", "生产"),
        ("record", "记录"), ("reduce", "减少"), ("respect", "尊敬"), ("solve", "解决"),
        ("spend", "花费"), ("trust", "信任"),
    ],
    6: [
        ("advantage", "优势"), ("attitude", "态度"), ("balance", "平衡"), ("benefit", "益处"),
        ("challenge", "挑战"), ("character", "性格"), ("community", "社区"), ("condition", "状况"),
        ("confidence", "信心"), ("decision", "决定"), ("distance", "距离"), ("effect", "影响"),
        ("effort", "努力"), ("enemy", "敌人"), ("event", "事件"), ("example", "例子"),
        ("future", "未来"), ("goal", "目标"), ("habit", "习惯"), ("idea", "主意"),
        ("industry", "工业"), ("language", "语言"), ("level", "水平"), ("material", "材料"),
        ("method", "方法"), ("moment", "时刻"), ("nature", "大自然"), ("period", "时期"),
        ("pleasure", "快乐"), ("population", "人口"), ("pressure", "压力"), ("progress", "进步"),
        ("quality", "质量"), ("relationship", "关系"), ("situation", "情况"), ("society", "社会"),
        ("standard", "标准"), ("success", "成功"), ("suggestion", "建议"), ("technology", "技术"),
        ("trouble", "麻烦"), ("value", "价值"), ("victory", "胜利"), ("courage" , "勇气"),
    ],
}

for _g, _items in WORDS_EXTRA.items():
    WORDS[_g] = list(dict.fromkeys(list(WORDS.get(_g, [])) + _items))

# 新加的可数名词
NOUNS_EXTRA = {
    2: ("ball", "doll", "kite", "car", "bus", "bike", "boat", "plane", "train",
        "cup", "box", "key", "clock", "phone", "bottle", "spoon", "fork", "bowl",
        "plate", "cow", "pig", "hen", "bee", "ant", "frog", "snake", "bear"),
    3: ("giraffe", "zebra", "camel", "wolf", "fox", "goat", "dolphin", "whale",
        "penguin", "jacket", "dress", "skirt", "shirt", "sweater", "sock", "glove",
        "scarf", "mountain", "river", "lake", "sea", "island", "forest", "field",
        "cloud", "shoe", "stone", "leaf", "baby"),
    4: ("story", "picture", "photo", "letter", "card", "gift", "party", "game",
        "rule", "sign", "corner", "market", "desert", "ocean"),
    5: ("activity", "competition", "concert", "exhibition", "journey", "opinion",
        "plan", "report", "speech", "trip", "topic"),
    6: ("advantage", "attitude", "benefit", "challenge", "character", "community",
        "condition", "decision", "distance", "effect", "effort", "enemy", "event",
        "example", "goal", "habit", "idea", "industry", "language", "level",
        "material", "method", "moment", "period", "population", "pressure",
        "quality", "relationship", "situation", "standard", "suggestion",
        "technology", "trouble", "value", "victory"),
}

for _g, _items in NOUNS_EXTRA.items():
    NOUNS[_g] = list(dict.fromkeys(list(NOUNS.get(_g, [])) + list(_items)))
