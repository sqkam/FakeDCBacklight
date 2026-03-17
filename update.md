# FakeDCBacklight 升级记录

这份文档记录了本次为了适配 Android 16 所做的修改，以及以后继续升级时可以复用的方法。

## 1. 本次升级的核心结论

这次不能在 Android 16 正常工作的根因，不是单一问题，而是两条链路同时发生了变化：

1. 显示亮度下发的 hook 点方法签名变了。
2. `Reduce Bright Colors` 的系统刷新链路变了。

所以升级时不能只改一个方法签名，必须把“亮度截断”和“RBC 联动”一起改。

## 2. 我是怎么定位问题的

### 2.1 先看本项目的核心实现

本项目核心逻辑都在：

- `app/src/main/java/com/ztc1997/fakedcbacklight/Hook.kt`
- `app/src/main/java/com/ztc1997/fakedcbacklight/SettingsActivity.kt`

其中真正影响功能的是 `Hook.kt`，因为模块本质上是在系统进程 `android` 中 hook 显示服务。

### 2.2 再对照 Android 新版本源码

升级这类 Xposed/LSPosed 模块时，最重要的一步不是先猜，而是直接对照 AOSP 当前版本源码。

本次重点对照了两个类：

- `com.android.server.display.LocalDisplayAdapter$LocalDisplayDevice`
- `com.android.server.display.DisplayPowerController`

重点看这几个方法：

- `requestDisplayStateLocked(...)`
- `applyReduceBrightColorsSplineAdjustment()`
- `handleRbcChanged()`

### 2.3 定位到的 Android 16 差异

#### 差异一：`requestDisplayStateLocked(...)` 签名变化

旧逻辑只 hook 了固定 3 参数版本。

Android 16 中该方法已经变成新的重载形式，至少包含：

- `state`
- `brightnessState`
- `sdrBrightnessState`
- 以及额外参数

这意味着旧代码可能根本 hook 不到新系统的方法，模块自然就失效。

#### 差异二：亮度不再只看一个值

Android 16 的显示亮度流程里同时使用：

- `brightnessState`
- `sdrBrightnessState`

如果只改其中一个参数，另一个参数仍然可能让系统走到不符合预期的亮度路径，所以必须一起处理。

#### 差异三：`applyReduceBrightColorsSplineAdjustment()` 不能再粗暴拦截

旧代码里把：

- `applyReduceBrightColorsSplineAdjustment()`
- `handleRbcChanged()`

都整个拦掉。

但 Android 16 里 `applyReduceBrightColorsSplineAdjustment()` 不只是“RBC 计算”，它还会继续触发显示刷新链路。如果把它整个拦掉，系统后续状态更新可能也被截断。

所以新的思路应该是：

- 保留系统的刷新链路
- 只拦掉会和本模块逻辑冲突的那部分 RBC 自动重算

## 3. 本次代码是怎么改的

## 3.1 改法一：hook 所有 `requestDisplayStateLocked` 重载

旧写法是固定参数签名的 `findAndHookMethod(...)`。

新写法改成：

- `XposedBridge.hookAllMethods(localDisplayDevice, "requestDisplayStateLocked", ...)`

这样做的好处：

- Android 旧版本和新版本的不同重载都能命中
- 系统内部参数继续变化时，更不容易直接失效

## 3.2 改法二：同时处理 `brightnessState` 和 `sdrBrightnessState`

在 `beforeHookedMethod` 中：

1. 从 `param.args[1]` 读取 `brightnessState`
2. 从 `param.args[2]` 读取 `sdrBrightnessState`
3. 用两者中有效且更低的那个值作为判定亮度
4. 如果低于设定阈值，则把两个值都钳到 `minScreenBright`

这样可以保证：

- 伪 DC 判断更符合 Android 16 的真实显示流程
- SDR/HDR 相关路径不会一边被改、一边没改

本次实现逻辑大意如下：

```kotlin
val targetBright = param.args.getOrNull(1) as? Float ?: return
val targetSdrBright = param.args.getOrNull(2) as? Float
val requestedBright = listOfNotNull(targetBright, targetSdrBright)
    .filter { it >= 0f }
    .minOrNull() ?: targetBright

if (requestedBright < minScreenBright) {
    param.args[1] = maxOf(targetBright, minScreenBright)
    if (targetSdrBright != null) {
        param.args[2] = maxOf(targetSdrBright, minScreenBright)
    }
}
```

## 3.3 改法三：保留系统 RBC 刷新链，只拦冲突部分

旧代码会直接把 `applyReduceBrightColorsSplineAdjustment()` 整体拦掉。

这次改成：

- 不再 hook `applyReduceBrightColorsSplineAdjustment()`
- 只 hook `handleRbcChanged()`

并且在 `handleRbcChanged()` 中：

1. 判断模块是否启用
2. 读取系统 `mCdsi`
3. 手动同步 `mIsRbcActive`
4. 返回 `param.result = null`，阻止系统继续用自己的 RBC spline 覆盖本模块逻辑

这样做的效果是：

- Android 16 的显示更新链还在
- 本模块仍然接管低亮度下的 Extra Dim 联动
- 不会再因为把整个刷新入口拦掉而导致新系统行为异常

## 3.4 改法四：统一 RBC 开关方式

旧逻辑有些分支只改：

- `reduce_bright_colors_level`

但没有始终明确改：

- `reduce_bright_colors_activated`

在新系统里，这种写法更容易出现“level 已经变了，但 activated 状态不一致”的问题。

所以我新增了两个辅助方法：

- `updateReduceBrightColors(ctx, level)`
- `disableReduceBrightColors(ctx)`

统一约束为：

### 低亮度进入伪 DC 区间时

- 写入 `reduce_bright_colors_level`
- 写入 `reduce_bright_colors_activated = 1`

### 退出伪 DC 区间或关闭模块时

- 写入 `reduce_bright_colors_level = 0`
- 写入 `reduce_bright_colors_activated = 0`

这样状态更完整，也更适合后续版本继续适配。

## 3.5 改法五：补上 hook 容错

系统进程里的 hook 最怕因为空对象、字段变化或参数异常直接抛错。

所以本次在关键 hook 中加了：

- `runCatching { ... }.onFailure(XposedBridge::log)`

这样以后系统升级后就算又有字段变化，也能先从日志里看到失败点，而不是完全黑盒。

## 4. 以后继续升级时，建议照这个流程来

以后你升级到 Android 17、Android 18，或者某个厂商系统大版本时，建议固定按下面步骤做：

### 步骤一：先确认模块失效在哪一层

优先判断是：

- 根本没 hook 到方法
- hook 到了，但参数语义变了
- hook 到了，但系统后续链路变了

判断方式：

- 看 LSPosed/Xposed 日志
- 在关键 hook 点加日志
- 对比系统源码确认类名、方法名、字段名、参数顺序

### 步骤二：优先看 AOSP 源码，而不是只靠猜

重点找：

- 原来 hook 的类还在不在
- 方法是否改名
- 方法是否变成新重载
- 参数位置是否变化
- 原先被你拦掉的方法，现在是否还承担“通知/刷新/状态同步”的职责

对这种系统模块来说，AOSP 对照几乎是必做项。

### 步骤三：固定签名 hook 优先改成“按方法名兼容重载”

如果系统方法在新旧版本之间经常改参数，优先考虑：

- `hookAllMethods(...)`

然后在回调里根据 `param.args` 动态取值。

这样比写死参数签名更耐升级。

### 步骤四：检查“被拦的方法”是不是还承担别的职责

这是这次升级里最重要的经验之一。

很多旧版本中可以整段拦掉的方法，在新版本里可能额外承担了：

- 触发刷新
- 发送消息
- 更新状态位
- 通知其他控制器

如果仍然整段拦掉，就会出现“旧逻辑看似还在，但系统不再刷新”的问题。

所以升级时要问自己两个问题：

1. 这个方法现在除了旧功能外，还做了什么？
2. 我到底要拦整个方法，还是只拦其中和模块冲突的部分？

### 步骤五：成对处理相关状态

像本项目这种依赖系统设置项联动的逻辑，升级时要避免只改一半状态。

例如 RBC 至少要关注：

- `reduce_bright_colors_level`
- `reduce_bright_colors_activated`

以后如果 Android 再增加别的状态位，也要一起纳入，而不是只写一个数值。

### 步骤六：升级完一定要实际编译

不要只改代码不编译。

本次编译前还额外解决了两个环境问题：

- 增加 `local.properties` 指向本机 SDK
- 在 `build.gradle` 中增加 Xposed Maven 仓库

否则项目甚至无法正常打包验证。

## 5. 本次为了能成功编译，额外做了什么

## 5.1 增加 `local.properties`

内容为：

```properties
sdk.dir=/Users/sqkam/Library/Android/sdk
```

作用：

- 让 Gradle 能找到本机 Android SDK

## 5.2 增加 Xposed 仓库

在根目录 `build.gradle` 的 `allprojects.repositories` 中增加：

```gradle
maven { url 'https://api.xposed.info/' }
```

作用：

- 让 `de.robv.android.xposed:api:82` 可以正常解析下载

## 5.3 可用 NDK

本机可用 NDK 路径为：

```text
/Users/sqkam/Library/Android/sdk/ndk/24.0.8215888
```

本项目当前没有用到需要单独调整的 NDK 构建逻辑，但确认可用版本仍然是升级时应做的一步。

## 6. 以后你可以直接复用的升级模板

如果将来再遇到“新 Android 版本上失效”，可以按下面模板操作：

1. 找出模块真正生效的 hook 类和 hook 方法。
2. 去 AOSP 对照新系统源码。
3. 先确认方法签名、参数、字段名有没有变化。
4. 再确认被拦的方法是不是新增了刷新职责。
5. 如果参数容易变，改成 `hookAllMethods(...)`。
6. 如果是多参数亮度流程，相关参数要一起处理。
7. 如果是系统状态联动，相关状态位要成套写入和清理。
8. 在关键 hook 外加日志和容错。
9. 解决构建依赖和 SDK/NDK 环境问题。
10. 实机验证，不要只看能不能编译通过。

## 7. 这次升级后，模块行为可以概括为

### 低亮度时

- 计算实际参与判定的亮度
- 写入 Extra Dim 强度
- 激活 RBC
- 把系统亮度参数钳到最小安全亮度

### 亮度恢复或关闭模块时

- 关闭 RBC
- 清空 RBC 强度
- 让系统亮度恢复正常流程

### 在 Android 16 上

- 兼容新的 `requestDisplayStateLocked(...)` 重载
- 兼容 `brightnessState + sdrBrightnessState` 双参数路径
- 不再错误拦截完整的 RBC 刷新链

## 8. 后续还可以继续优化的点

虽然这次已经能正常用，但以后如果要继续做得更稳，可以再考虑：

- 给关键 hook 点增加更明确的 `XposedBridge.log(...)`
- 把设置界面中已废弃的 `PreferenceFragment` 迁移到新版实现
- 把 `compileSdkVersion` / `targetSdkVersion` / AGP 继续升级
- 针对厂商 ROM 再补一层差异兼容

---

一句话总结：这次升级成功的关键，不是“把一个失效方法改成新签名”，而是“先对照 AOSP 找出 Android 16 的完整亮度与 RBC 链路变化，再把 hook 点、参数处理和状态联动一起调整”。
