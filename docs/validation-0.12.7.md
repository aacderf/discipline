# 自律 0.12.7：限额入口与机械反馈

首页新增 48dp 应用限额状态条；统计页提供应用限额和使用计划双入口。计划管理沿用数量副标题、右下角加号和单行地点工具栏，未生成任务以状态胶囊呈现。截止、全天/有效时段和地点聚合为元信息胶囊。

分析页默认显示过去七天概览、已选应用摘要和下周计划。长应用列表移至独立选择页；手动安排和 AI 建议入口位于首屏，口径说明与目标配置按需展开。数据读取、AI 上传确认、密钥设置、保存/停用/删除周计划的调用保持原样。

限额摘要在 IO 线程复用 effectiveLimits 与 AppUsageLimits.read，按可见生命周期更新；无授权、未运行、未提供记录和余额用尽分别说明。每日/每周耗尽判断复用原有 dailyBlockEnd/weeklyBlockEnd，应急解锁时显示解锁状态。未来周规则不作为当前生效规则。

五处有限反馈已实现：开始/新轮次的沙漏 180° 翻转并对称复位；成功完成后的卡片压印、220ms 标题划线与 PathMeasure 勾线；数字向上退场、从下方弹簧归位；实际设限/锁止时的双侧闸门标签；有效手绘闭合后的 220ms 线宽与顶点圆环脉冲。沙漏暂停/继续和恢复页面不重播；原有开始操作触觉复用，新轮次自动切换才补一次触觉。初次限额查询不伪装成锁止事件。首页压印作用于包含边框的完整外壳，内部不叠加缩放；划线保留旧卡时读取刚保存的进度，业务操作不等待动画。完成事件只消费一次，重新开启动画不重播。

关闭动画时，数字、沙漏旋转、压印、勾线、闸门与地图反馈直接显示终态。没有新增依赖、背景模糊或循环装饰动画。计时数字保持原位；透明度、尺度与旋转读值置于绘制层。

## 验证

- Debug、AndroidTest、Release 构建通过；lint 为 0 错误、58 条已有警告。
- 10 项相关设备测试通过：两个首页/统计直达入口、初次/变更/关闭动画的闸门反馈、恢复/暂停继续/新轮次沙漏反馈、完成印章终态、完成成功与失败及立即保存进度、默认下周计划直达、配置入口与固定专注按钮、进度条完整边缘、快速切页，以及地图删除/清空/撤销只修改草稿。
- 初版 9 项结果在 work/ui-0.12.7-tests.log；新增实际完成/失败回归在 work/ui-0.12.7-success-test-delivery.log。初次运行新增测试的失败来自测试时钟/未合并语义树选择，修正测试后通过；应用构建不受影响。
- 320dp 下检查了浅色标准字号的首页状态条、统计双入口与分析首屏，以及深色 1.3 倍字号的首页状态条和统计双入口；双入口标题平衡断行，避免最后一行孤字。
- 全项目原有 28 处 testTag 与 64 段显式 semantics 保留。其他现有源文件按字节比对未变，包括 Engine、Store、BlockService、LocationService、围栏算法、限额与统计仓库。
- 地图 back/beginTrace/cancelTrace/removeSelectedVertex/clearSelection/undoBoundaryEdit/save 保持原函数；新脉冲只捕获成功闭合后的屏幕坐标绘制反馈，平移地图或清除图层时取消。原有放弃修改确认、一次撤销、保存及坐标换算保留。
- 签名证书与 0.12.6 一致；专用模拟器上覆盖安装到 versionCode 52 / 0.12.7，启动前两个数据库文件逐字节一致，安装后冷启动成功。

本轮结果来自 DisciplineQA36 模拟器；未在用户 iQOO 上验证触觉、手绘反馈及帧率，没有新做整套性能基准，也没有改限额算法或后台拦截保证。真机手绘与触觉、完整 TalkBack 操作仍需实际设备体验，现有终态/字体/不透明转场实现保留。

## 完整代码位置

UI-0.12.7-完整代码.md 包含下表 12 个文件的完整代码与修改后行号；UI-0.12.7-完整源码.zip 为对应源码和两份相关测试。适用于当前项目，不包含密钥、签名私钥或个人数据库。

| 完整文件 | 修改后函数行号 |
|---|---|
| ComposeMainActivity.kt | TodayContent：869；TaskCard：1200；PlansScreen：1362 |
| ComposeFocusStats.kt | FocusScreen：94；StatsScreen：399；AnimatedStatDuration：734 |
| ComposeUsageInsights.kt | UsageInsightsScreen：40 |
| ComposeAppLimits.kt | AppLimitsScreen：29 |
| ComposeHourglass.kt | HourglassInstrument：35 |
| ComposeMotion.kt | TaskTypeGlyph：215 |
| ComposeFeedback.kt | CompletionStroke：64 |
| ComposePhysics.kt | precisionNumberTransform：13 |
| ComposePlaceActivity.kt | MapScreen：246；finishTrace：579 |
| ComposeLimitStatus.kt | readLimitOverview：43；rememberLimitOverview：64；AppLimitStatusBar：76；LimitGateBadge：97；ControlEntryCard：134 |
| ComposeMechanicalMotion.kt | rememberCompletionStamp：24；rememberHourglassTurn：70；rememberFocusTurn：89 |
| ComposeBoundaryFeedback.kt | BoundarySnapFeedback：20 |
