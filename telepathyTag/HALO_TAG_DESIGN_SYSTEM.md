# Halo Tag UI 设计系统

> Jetpack Compose (Material 3) 实现 | 暗色主题 | 蓝牙寻物雷达 App

---

## 一、设计理念

**关键词**：硬核、科技感、极简、暗色玻璃态

- **色彩基调**：暗绿 → 纯黑渐变，霓虹绿点缀，营造专业追踪设备的精准感
- **卡片风格**：暗色玻璃态（半透明深灰 + 圆角 + 微阴影），低调有层次
- **排版风格**：SansSerif 轻盈字重，接近 San Francisco 的呼吸感
- **动效哲学**：流畅自然，不抢戏——绿色脉冲波纹暗示"正在搜索"，弹簧箭头暗示"物理指向"

---

## 二、配色方案

### 2.1 渐变色

| Token | 色值 | 用途 |
|-------|------|------|
| `GradientDarkGreen` | `#0D1F17` | 渐变起点 — 暗绿 |
| `GradientMid` | `#091510` | 渐变中点 — 深绿黑过渡（仅扫描页） |
| `GradientBlack` | `#060A08` | 渐变终点 — 纯黑 |

**使用方式**：
```kotlin
// 扫描页（三段）
Brush.verticalGradient(colors = listOf(GradientDarkGreen, GradientMid, GradientBlack))

// 雷达页顶部栏（两段）
Brush.verticalGradient(colors = listOf(GradientDarkGreen, GradientBlack))
```

### 2.2 背景 / 表面色

| Token | 色值 | 用途 |
|-------|------|------|
| `BackgroundBlack` | `#080C0A` | 页面底色 |
| `CardDark` | `#151918` | 暗色卡片 |
| `CardGlass` | `0xEE151918` | 玻璃态暗色卡片（~93% 不透明度） |
| `SurfaceDark` | `#111615` | 暗灰表面 |

### 2.3 主色调 — 霓虹绿

| Token | 色值 | 用途 |
|-------|------|------|
| `PrimaryGreen` | `#00E676` | 主色 — 按钮渐变起点、强调文字、箭头 |
| `PrimaryGreenDark` | `#00C853` | 辅色 — 按钮渐变终点 |
| `AccentGreen` | `#69F0AE` | 点缀 — 脉冲波纹（远距离时） |
| `ButtonGreenStart` | `#00E676` | Play Sound 按钮渐变起 |
| `ButtonGreenEnd` | `#00B248` | Play Sound 按钮渐变止 |
| `ButtonDarkStart` | `#2A2F2C` | Direction 按钮渐变起 |
| `ButtonDarkEnd` | `#1A1F1C` | Direction 按钮渐变止 |

### 2.4 文字色

| Token | 色值 | 用途 |
|-------|------|------|
| `TextPrimary` | `#E8EAE9` | 主文字 — 浅灰白（暗底上） |
| `TextSecondary` | `#9EA7A3` | 辅助文字 |
| `TextMuted` | `#5A635F` | 弱文字 / 占位 |
| `TextOnDark` | `#FFFFFF` | 渐变背景上的文字（白色） |

### 2.5 状态色

| Token | 色值 | 用途 |
|-------|------|------|
| `StatusNear` | `#00E676` | 近距离 / 已连接 |
| `StatusFar` | `#FFAB40` | 中等距离 / 警告 |
| `StatusLost` | `#FF5252` | 错误 / 断开 / 超时 |
| `StatusDotGreen` | `#4ADE80` | 连接指示灯亮 |
| `StatusDotRed` | `#F87171` | 连接指示灯灭 |
| `StatusDotOrange` | `#FFAB40` | 连接中指示灯 |

### 2.6 雷达专用色

| Token | 色值 | 用途 |
|-------|------|------|
| `RadarRingGreen` | `0x1A00E676` | 同心环线（~10% 绿） |
| `RadarRingOuter` | `0x2200E676` | 外环描边 |
| `RadarCenterDot` | `0xCC00E676` | 中心点（80% 绿） |
| `RadarBgCircle` | `#0F1A15` | 雷达底色（极深绿） |

### 2.7 信号强度

| Token | 色值 | 触发条件 |
|-------|------|---------|
| `SignalStrong` | `#4ADE80` | RSSI > -60 dBm |
| `SignalMedium` | `#FFAB40` | RSSI -60 ~ -75 dBm |
| `SignalWeak` | `#F87171` | RSSI < -75 dBm |

---

## 三、排版系统

全部使用 `FontFamily.SansSerif`。Android 上通常映射为 Roboto。

| Token | 字重 | 字号 | 行高 | 字间距 | 典型用途 |
|-------|------|------|------|--------|---------|
| `displayLarge` | Light 300 | 48sp | 56sp | -0.5sp | 距离读数 "3.5" |
| `displayMedium` | Light 300 | 40sp | 48sp | -0.5sp | （保留） |
| `displaySmall` | Normal 400 | 32sp | 40sp | 0sp | 角度读数 "45" |
| `headlineLarge` | SemiBold 600 | 28sp | 36sp | 0sp | 页面标题 "Halo Tag" |
| `headlineMedium` | SemiBold 600 | 24sp | 32sp | 0sp | （保留） |
| `headlineSmall` | Medium 500 | 20sp | 28sp | 0sp | 距离单位 "m"、"°" |
| `titleLarge` | Medium 500 | 20sp | 28sp | 0sp | （保留） |
| `titleMedium` | Medium 500 | 16sp | 24sp | 0.15sp | 卡片标题、状态文字 |
| `titleSmall` | Medium 500 | 14sp | 20sp | 0.1sp | 列表项标题 |
| `bodyLarge` | Normal 400 | 16sp | 24sp | 0.5sp | 副标题正文 |
| `bodyMedium` | Normal 400 | 14sp | 20sp | 0.25sp | 描述文字 |
| `bodySmall` | Normal 400 | 12sp | 16sp | 0.4sp | MAC 地址、辅助信息 |
| `labelLarge` | Medium 500 | 14sp | 20sp | 0.1sp | 按钮文字 |
| `labelMedium` | Medium 500 | 12sp | 16sp | 0.5sp | 标签、计数 |
| `labelSmall` | Medium 500 | 11sp | 16sp | 0.5sp | 信标名、小标签 |

---

## 四、Material 3 主题

```kotlin
// 固定暗色主题，关闭动态颜色
darkColorScheme(
    primary             = #00E676    // PrimaryGreen
    onPrimary           = #000000    // 黑色
    primaryContainer    = #003D1A
    onPrimaryContainer  = #B0FFD0
    inversePrimary      = #00C853    // PrimaryGreenDark
    secondary           = #69F0AE    // AccentGreen
    onSecondary         = #000000
    secondaryContainer  = #003D20
    onSecondaryContainer = #B0FFD0
    tertiary            = #1DE9B6
    onTertiary          = #000000
    background          = #080C0A    // BackgroundBlack
    onBackground        = #E8EAE9    // TextPrimary
    surface             = #151918    // CardDark
    onSurface           = #E8EAE9
    surfaceVariant      = #111615    // SurfaceDark
    onSurfaceVariant    = #9EA7A3    // TextSecondary
    surfaceTint         = PrimaryGreen @ 8%
    error               = #FF5252    // StatusLost
    onError             = #FFFFFF
    errorContainer      = #4A1010
    onErrorContainer    = #FFCCCC
    outline             = #2A332F
    outlineVariant      = #1A2420
    inverseSurface      = #E0E0E0
    inverseOnSurface    = #1A1A1A
)
```

约束：
- `dynamicColor = false` — 必须关闭
- `darkTheme = true` — 固定暗色
- 启动 xml 主题设为 `android:Theme.Material.NoActionBar` + `windowBackground = #080C0A` + `windowLightStatusBar = false`

---

## 五、页面一：设备扫描页 `BleScanScreen`

### 5.1 布局结构

```
┌────────────────────────────────┐
│                                │
│  🟢 全屏渐变背景               │
│  [DarkGreen → Mid → Black]     │
│                                │
│  Halo Tag            (28sp 白) │
│  Find your items...  (16sp 70%白)│
│                                │
│  ┌──────────────────────┐      │
│  │  🔍 Scan for Tags    │      │ ← 暗色按钮 56dp 高
│  │  (或 Scanning... +   │      │   圆角 18dp
│  │   绿色旋转圈)         │      │   shadow 8dp
│  └──────────────────────┘      │
│                                │
│  Searching... / N device(s)    │ ← 半透明白色小字
│                                │
│  ┌──────────────────────┐      │
│  │ 🟢 QNIS Base         │      │ ← 暗色玻璃态卡片
│  │    MAC: XX:XX:XX:XX  │      │   圆角 16dp
│  │              -55 dBm │      │   入场：fadeIn + slideIn
│  │              ▂▄▆█    │      │   交错延迟 80ms/项
│  └──────────────────────┘      │
│  ┌──────────────────────┐      │
│  │ 🟡 ...               │      │
│  └──────────────────────┘      │
└────────────────────────────────┘
```

### 5.2 子组件规格

#### 搜索按钮

| 属性 | 值 |
|------|-----|
| 高度 | 56dp |
| 圆角 | 18dp（`RoundedCornerShape(18.dp)`） |
| 阴影 | `shadow(8.dp, RoundedCornerShape(18.dp))` |
| 背景 | `CardDark`（深灰黑） |
| 文字 | `PrimaryGreen`，"🔍 Scan for Tags"，`labelLarge` + SemiBold |
| 加载态 | 绿色 `CircularProgressIndicator` 22dp + "Scanning for tags..." |

#### DeviceCard（设备卡片）

| 属性 | 值 |
|------|-----|
| 背景 | `CardGlass`（`0xEE151918`） |
| 圆角 | 16dp |
| 阴影 | 2dp |
| 内边距 | 16dp |
| 左侧信号灯 | 12dp 实心圆，颜色 = f(RSSI) |
| 标题 | `titleMedium` + `TextPrimary` + SemiBold |
| MAC 地址 | `bodySmall` + `TextMuted` |
| RSSI 数值 | `labelSmall` + 信号色 + Medium |
| 信号柱状图 | `SignalBars` 组件，4 级阶梯 |

#### SignalBars（信号强度柱）

| RSSI 范围 | 亮柱数 | 颜色 |
|-----------|--------|------|
| > -55 dBm | 4 | `SignalStrong` #4ADE80 |
| -55 ~ -65 | 3 | `SignalStrong` |
| -65 ~ -75 | 2 | `SignalMedium` #FFAB40 |
| -75 ~ -85 | 1 | `SignalWeak` #F87171 |
| < -85 | 0 | — |

每柱宽 4dp，高度 6/11/16/21dp，间距 2dp，圆角 2dp。未亮柱用 `#2A332F`。

#### 空状态

居中显示：
- 📡 emoji（48sp）
- "No devices found"（`bodyLarge`, 50% 白）
- "Tap scan to search for nearby tags"（`bodySmall`, 35% 白）

---

## 六、页面二：雷达追踪页 `HaloTagRadarScreen`

### 6.1 布局结构

```
┌──────────────────────────────────┐
│  🟢 渐变顶部栏                    │
│  [DarkGreen → Black]              │
│                                  │
│  ● Halo Tag      Disconnect     │ ← 连接指示 + 断开按钮
│    已连接                        │
├──────────────────────────────────┤
│                                  │
│          ┌──────────┐            │
│          │ ○ ○ ○    │            │ ← 260dp 雷达圆
│          │  ╲ │ ╱   │            │   绿色同心环+脉冲
│          │   ╲│╱    │            │
│          │ ───●───  │            │
│          │   ╱│╲    │            │
│          │  ╱ │ ╲   │            │
│          │ ╱  │  ╲  │            │
│          └──────────┘            │
│                                  │
│          TRACKING                │ ← 状态文字（3sp 字间距）
│          3.5 m                   │ ← 距离（48sp Light）
│          45 °                    │ ← 角度（32sp Normal）
│          NE ↗                    │ ← 方位（12sp）
│                                  │
│  ┌────────────────────────┐      │
│  │ 🔑 Halo Keyring        │      │ ← InfoCard 暗色玻璃态
│  │ STATUS: Near (4.0 m)   │      │   圆角 20dp
│  │ Last Seen: Home (2m)   │      │
│  └────────────────────────┘      │
│                                  │
│  ┌──────────┐ ┌──────────┐      │
│  │🔊 Play   │ │🧭 Direction│      │ ← 双按钮 56dp
│  │ Sound    │ │          │      │   绿色渐变 / 暗色
│  └──────────┘ └──────────┘      │
│                                  │
│  MY DEVICES                      │ ← 标签（1.5sp 字间距）
│  ┌────────────────────────┐      │
│  │ Halo Keyring   ● green │      │ ← 当前设备高亮
│  └────────────────────────┘      │
│  ┌────────────────────────┐      │
│  │ Backpack        ○ gray │      │ ← 占位设备
│  └────────────────────────┘      │
│  ┌────────────────────────┐      │
│  │ Wallet          ○ gray │      │
│  └────────────────────────┘      │
└──────────────────────────────────┘
```

### 6.2 状态映射

| `FindingStatus` | 雷达内显示 | 状态文字 | 距离/角度 | 脉冲波纹 | InfoCard 状态 |
|-----------------|-----------|---------|----------|---------|---------------|
| `IDLE` | 🔑 图标 | "READY"（灰） | 隐藏 | 关闭 | "Ready to search" |
| `SENDING` | 绿色旋转圈 | "SEARCHING..."（灰） | 隐藏 | 关闭 | "Waking base station..." |
| `SUCCESS` (>1m) | 绿色指南针箭头 | "TRACKING"（绿色） | 显示 | 慢速 1.8s 淡绿 | "Near (Approx. X.X meters)" |
| `SUCCESS` (<1m) | 绿色指南针箭头 | "NEARBY"（绿色） | 距离变绿 | 快速 1s 亮绿 | "Near (Approx. X.X meters)"（绿） |
| `FAILED` | 🔑 图标 | "WRITE ERROR"（红） | 隐藏 | 关闭 | "Write error — retry"（红） |
| `TIMEOUT` | 🔑 图标 | "TIMEOUT"（红） | 隐藏 | 关闭 | "No response from tag"（红） |

### 6.3 顶部栏规格

| 属性 | 值 |
|------|-----|
| 背景 | `verticalGradient(GradientDarkGreen, GradientBlack)` |
| 顶部间距 | 48dp（status bar 安全区） |
| 水平内边距 | 20dp |
| 竖直内边距 | 14dp |
| 连接指示灯 | 8dp 实心圆：成功=绿，连接中=橙，断开=红 |
| 小标题 | "Halo Tag"，`titleSmall`，白色 70% |
| 状态文字 | `labelMedium` + SemiBold，白色 100% |
| Disconnect | `TextButton`，`labelMedium`，白色 70% |

### 6.4 雷达圆 `RadarDisplay` 规格

| 元素 | 绘制方式 | 参数 |
|------|---------|------|
| 背景圆 | `drawCircle` 填充 | 色 `RadarBgCircle`（#0F1A15），半径 = canvas 最小边 / 2 |
| 外环描边 | `drawCircle` Stroke | 色 `#1A3328`，线宽 1.5dp |
| 3 层同心环 | `drawCircle` Stroke ×3 | 色 `RadarRingGreen`（`0x1A00E676`），半径 = maxR × 1/3, 2/3, 3/3 |
| 十字线 | `drawLine` ×2 | 色 `White @ 15%`，线宽 0.5dp |
| 12 个刻度 | `drawLine` 循环 | 每 30°；主刻度 (N/E/S/W) 线更长更亮 |
| 扫描弧 | `drawArc` sweepGradient | 色绿 `0.4→0.15→0`，65° 扇面，2.4s 旋转一周 |
| 中心点 | `drawCircle` ×2 | 外圈 6dp 绿色/暗绿，内圈 3dp 深黑 |
| 脉冲波纹 ×2 | `drawCircle` Stroke | 交替相位（时差=period/2），从 0.15→0.95 缩放 |

**脉冲波纹动画参数**：

```
if (active && distance < 1m):
    duration = 1000ms, color = PrimaryGreen (亮绿)
else:
    duration = 1800ms, color = AccentGreen (淡绿)

波纹1: radius 0.15→0.95, alpha 0.5→0, EaseOutCubic, Restart
波纹2: radius 0.1→0.9,  alpha 0.45→0, EaseOutCubic, Restart + StartOffset(duration/2)
```

### 6.5 数据展示区

成功状态下雷达圆下方三行数据：

| 行 | 示例 | 字号 | 字重 | 颜色 |
|----|------|------|------|------|
| 距离 | **3.5** m | 48sp / 20sp | Light / Medium | <1m 绿，否则白 |
| 角度 | **45** ° | 32sp / 20sp | Normal / Medium | 绿色 |
| 方位 | **NE ↗** | 12sp | Medium | 弱灰 |

方位自动换算规则：

| 角度范围 | 标签 |
|----------|------|
| 337.5°~360°, 0°~22.5° | N ↑ |
| 22.5°~67.5° | NE ↗ |
| 67.5°~112.5° | E → |
| 112.5°~157.5° | SE ↘ |
| 157.5°~202.5° | S ↓ |
| 202.5°~247.5° | SW ↙ |
| 247.5°~292.5° | W ← |
| 292.5°~337.5° | NW ↖ |

### 6.6 指南针箭头 `ArrowPointer` 规格

由 `animateFloatAsState` + `spring(DampingRatioLowBouncy, StiffnessLow)` 驱动旋转。

#### 上半部（指北 / 目标方向）

```
Path:
  moveTo(0.50w, 0.00h)         ← 尖顶
  lineTo(0.72w, 0.38h)         ← 右肩
  lineTo(0.58w, 0.50h)         ← 右腰
  lineTo(0.50w, 0.50h)         ← 中心
  close()

填充: verticalGradient(#00E676 → #00C853 → #009624)
描边: PrimaryGreen @ 50%, 1.5dp
```

#### 下半部（指南 / 尾翼）

```
Path:
  moveTo(0.45w, 0.50h)         ← 左上腰
  lineTo(0.37w, 0.63h)         ← 左尾尖
  lineTo(0.46w, 0.58h)         ← 左内凹
  lineTo(0.50w, 0.72h)         ← 尾底
  lineTo(0.54w, 0.58h)         ← 右内凹
  lineTo(0.63w, 0.63h)         ← 右尾尖
  lineTo(0.55w, 0.50h)         ← 右上腰
  close()

填充: verticalGradient(#3A3F3C → #2A2F2C → #1A1F1C)
描边: White @ 15%, 1.2dp
```

#### 中心铆钉

```
外圈: 半径 9%w, #1A1F1C 填充
描边: 半径 9%w, #2A332F, 1.5dp
内圈: 半径 4%w, PrimaryGreen @ 40% 填充
```

### 6.7 InfoCard（信息卡片）

| 属性 | 值 |
|------|-----|
| 背景 | `CardGlass`（`0xEE151918`） |
| 圆角 | 20dp |
| 阴影 | 3dp |
| 水平外边距 | 24dp |
| 内边距 | 18dp |
| 左侧图标 | 44dp 圆形 `#0F1A15` 背景 + 🔑 emoji |
| 标题 | `titleMedium` + SemiBold + `TextPrimary` |
| 状态行 | `"STATUS: ..."` `bodySmall` + Medium + 状态色 |
| 最后位置 | `"Last Seen: Home (2 min ago)"` `bodySmall` + `TextMuted` |

### 6.8 ActionButton（操作按钮）

双按钮并排，水平间距 12dp，各 `Modifier.weight(1f)`。

| 属性 | Play Sound | Direction |
|------|-----------|-----------|
| 渐变色 | `[PrimaryGreen, PrimaryGreenDark]` | `[#2A2F2C, #1A1F1C]` |
| 渐变方向 | `horizontalGradient` | `horizontalGradient` |
| 文字 | "🔊 Play Sound" | "🧭 Direction" |
| 文字色 | White | White |
| 高度 | 56dp | 56dp |
| 圆角 | 18dp | 18dp |
| 阴影 | 6dp | 6dp |
| 禁用背景 | `SurfaceDark` | `SurfaceDark` |
| 点击缩放 | `animateFloatAsState` 1.0→0.97 spring | 同左 |

### 6.9 MyDeviceItem（设备列表项）

| 属性 | 当前设备 | 其他设备 |
|------|---------|---------|
| 背景 | `CardGlass` | `CardDark` |
| 圆角 | 14dp | 14dp |
| 阴影 | 2dp | 0dp |
| 水平外边距 | 24dp | 24dp |
| 竖直外边距 | 4dp | 4dp |
| 标题 | `titleSmall` + Medium + `TextPrimary` | 同左 |
| 状态文字色 | `StatusNear`（绿） | `TextMuted`（灰） |
| 右侧圆点 | `StatusDotGreen`（绿） | `#2A332F`（暗灰） |

---

## 七、动效清单

| 动画 | API | 时长 / 曲线 | 触发条件 |
|------|-----|----------|---------|
| 扫描弧旋转 | `rememberInfiniteTransition` + `animateFloat` | 2400ms Linear, Restart | 始终 |
| 脉冲波纹 ×2 | `rememberInfiniteTransition` + `animateFloat` | 1800ms / 1000ms EaseOutCubic, Restart | `isActive=true` |
| 箭头旋转 | `animateFloatAsState` | `spring(DampingRatioLowBouncy, StiffnessLow)` | `azimuthDegrees` 变化 |
| 雷达缩放 | `animateFloatAsState` | tween(500ms, EaseOutCubic) | 成功=1.0, 其他=0.95 |
| 按钮缩放 | `animateFloatAsState` | `spring(StiffnessMedium)` | 启用=1.0, 禁用=0.97 |
| 列表项入场 | `AnimatedVisibility` | fadeIn(400ms) + slideInVertically(400ms) | 设备出现，交错 80ms/项 |
| 背景色过渡 | `animateColorAsState` | 默认 tween | FindingStatus 切换 |

---

## 八、文件清单

```
app/src/main/java/com/example/uwbtag/
├── MainActivity.kt           # Activity + 全部 Composable UI
│   ├── BleScanScreen         # 扫描页
│   ├── DeviceCard            # 设备卡片
│   ├── SignalBars            # 信号柱状图
│   ├── HaloTagRadarScreen    # 雷达追踪页
│   ├── RadarDisplay          # 雷达圆（Canvas 绘制）
│   ├── ArrowPointer          # 指南针箭头（Canvas 绘制）
│   ├── InfoCard              # 暗色玻璃态信息卡片
│   ├── ActionButton          # 渐变操作按钮
│   └── MyDeviceItem          # MY DEVICES 列表项
│
└── ui/theme/
    ├── Color.kt              # 全部色值定义（黑+绿）
    ├── Type.kt               # 15 级排版 HaloTypography
    └── Theme.kt              # darkColorScheme + MyTag_testTheme

app/src/main/res/values/
└── themes.xml                # 暗色启动主题
```

---

## 九、复现检查清单

- [ ] `Color.kt` 使用黑+绿色系：渐变暗绿→黑、主色霓虹绿、暗色卡片、绿色雷达
- [ ] `Type.kt` 定义了完整的 15 级 `HaloTypography`
- [ ] `Theme.kt` 使用 `darkColorScheme`，`dynamicColor = false`
- [ ] `themes.xml` 设置为暗色背景 + 暗状态栏
- [ ] 扫描页：暗绿→黑渐变全屏背景、暗色搜索按钮、暗色玻璃态卡片、绿色信号灯
- [ ] 雷达页：暗绿→黑渐变顶部栏、260dp 雷达圆（深绿底色+绿色环线+绿色脉冲+绿色扫描弧）
- [ ] 雷达页：绿色指南针箭头（绿渐变上半+深灰尾翼+中心铆钉）
- [ ] 雷达页：距离（48sp Light）+ 角度（32sp Normal 绿色）+ 方位标签
- [ ] 雷达页：暗色玻璃态 InfoCard、绿色 Play Sound 按钮 + 暗色 Direction 按钮
- [ ] 雷达页："MY DEVICES" 列表，当前设备绿色高亮
- [ ] 5 种 FindingStatus 状态正确映射到 UI
- [ ] 脉冲波纹在距离 < 1m 时加速且变为亮绿色
