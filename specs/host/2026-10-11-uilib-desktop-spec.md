# UILib 独立桌面宿主规格

状态：已确认模块规格。共同平台边界遵循[核心规格](<../../../Qz-UILib-Core/specs/2026-10-07-uilib-core-contract-design.md>)。

## 1. 独立启动与产物

桌面归 Platform 独立仓，提供单窗口运行入口和独立分发产物，通过正式 Maven 依赖接入 Core，并组合所属字体适配、共享 GL、具体 LWJGL3 绑定与平台运行库。启动不要求游戏安装、游戏资源或 MC 类加载器。窗口、原生事件、剪贴板、GPU 上下文和运行循环归宿主；核心继续保持无平台。桌面使用现有 GLFW/LWJGL3 与正式 GL PaintPlan 回放能力，不另建 UI/字体/布局体系。桌面消费所属共享 GL 正式产物的对应编译输出，具体 MC/Angelica/Iris 兼容不进入桌面组合；LWJGL3ify 转换标记由 Minecraft 装配，仅作用于所需的具体绑定类，不为单类标记复制整组共享生产源码。安装器和多窗口管理不在本模块范围。共享设置页的独立桌面入口及配置编辑范围由[配置页面规格](<../../../Qz-UILib-Core/specs/config/2026-10-12-uilib-settings-page-design.md>)维护。

## 2. 页面与交互闭环

默认桌面入口挂载正式共享配置页；旧计数与行列表演示只作为显式测试 fixture，不进入正式宿主产物。业务仍可提供任意正式 ScenePage。页面工厂可借用已初始化的图像、配置会话与 SettingsConfiguration 及显式重载动作；这些服务归窗口生命周期，不新建状态域或配置权威。窗口与页面继续使用既有 UILib 标准控件，包括文本显示、可编辑文本、按钮和可滚动内容。业务状态与界面声明通过既有 ReactiveDomain/Signal/ScenePageSession 组织；窗口尺寸变化更新有效空间，命中与绘制使用同一布局事实，不重新构造页面补偿。

宿主将鼠标位置、按键、按钮、滚轮、文本提交、焦点变化及关闭事件转换为正式输入；不直接改控件内部编辑状态。窗口坐标与 framebuffer 坐标按当前尺寸转换，缩放变化影响呈现与命中。剪贴板沿核心输入端口接入。GLFW 文本提交使用 Unicode codepoint；嵌入宿主的 UTF-16 字符入口允许一对高低代理各带同一文本键事件，普通字母、数字、标点和 Space 的 DOWN/UP 不截断待配对字符，命令或导航 DOWN 才结束该联系，当前编辑目标改变仍沿原来源核验。编辑组合/IME 的接入范围须在正式接口及可运行验收场景中准确表达，不冒称已支持不可取得的平台预编辑信息。

键回调只将原生命名键、当次修饰键与 DOWN/UP、重复事实递交同一中性键入口；标准编辑及应用命令由 Core 统一解释。已知键的 release 也经原物理门及中性适配器递交，不能在宿主提前丢弃。字符和组字继续沿原文本入口，按键不合成文本。开屏和恢复可用性后须等待唯一 GLFW poll 耗尽历史并确认释放；失焦结束旧物理段，Enter 与小键盘 Enter 分别维护按压段。桌面 Tab、Enter、ESC 保留原单次 DOWN 条件；Space 的 DOWN 先参与编辑和声明快捷键，未消费时由 Core 登记原焦点及范围，只有合法完整 UP 且目标从未离开才激活。离开后返回不能复活按压，宿主不合成 click 或保存业务目标。

按压及焦点行为参考 React Aria 的[Press Events](<https://react-aria.adobe.com/blog/building-a-button-part-1.md>)与[Keyboard Focus Behavior](<https://react-aria.adobe.com/blog/building-a-button-part-3.md>)；Space 释放阶段及取消联系对照其[usePress 实现](<https://github.com/adobe/react-spectrum/blob/main/packages/react-aria/src/interactions/usePress.ts>)。复合控件的 Tab、方向、选择和弹层关闭由 Core 的当前合同决定，宿主只转换事实。Primary+UP/DOWN/PAGE_UP/PAGE_DOWN 也走同一 KEY 事件，由 Core 先解释原对应编辑命令，未消费后查询精确绑定，宿主不直接编辑或重投第二事件。命名键映射采用 [GLFW 官方键定义](<https://github.com/glfw/glfw/blob/master/include/GLFW/glfw3.h>)；嵌入宿主的 native physical 整数保持不透明，消费方按 [LWJGL 官方键定义](<https://github.com/LWJGL/lwjgl/blob/master/src/java/org/lwjgl/input/Keyboard.java>)转换命名 Key。支持 A–Z、F1–F12、主键区数字、常用标点及既有编辑导航键，保留旧枚举顺序和嵌入六参/八参入口。

组合页面可通过宿主服务登记关闭与快捷重载请求委托，Ctrl+R 先交中性键路径，只有当前批成功、未被编辑或应用命令消费且原范围仍为当前范围、无模态/组字隔离时才请求原重载动作。宿主在当前输入批次结算后转交；配置页统一决定确认及字段错误。返回目录和确认退出窗口使用各自明确完成动作，不能由原生入口绕过确认。结束窗口同时清理这些委托。

## 3. 配置与资源

桌面调用核心[对象配置能力](<../../../Qz-UILib-Core/specs/config/2026-10-11-uilib-config-spec.md>)，显式路径读取或首次生成 YAML。桌面操作可触发重载，变化沿配置状态订阅更新窗口标题、页面内容或主题及字体来源；页面无需关闭重开。重载事件属于桌面宿主，不将桌面按键或窗口类型引入核心。字体沿既有来源与代际更新机制，完整候选列表及显式空值行为不另定义。

## 4. 生命周期与失败

关闭窗口终止输入，结束页面和配置订阅，释放 GPU、字体 CPU/native、回调与窗口资源，再结束平台初始化；释放仍在有效上下文内完成，失败保持原始异常并完成其余必要清理。不增加自动重试、失败降级、旧资源恢复等分支。一次初始化中途失败也释放已取得资源；允许新进程再次启动。

## 5. 验收

完整 build 验证生产源集、独立分发与平台隔离。真实桌面运行验证窗口创建、正式页面绘制（包括字形实际像素）、按钮、文本提交、滚动、尺寸变化、YAML 修改后重载及关闭释放。命令键验收覆盖共享保存条件及实际文件、共享邮箱 Ctrl+F 后同批文字、编辑空操作/只读消费、模态结束后 repeat 隔离、旧持键与失焦释放门，以及原 Ctrl+R 确认、ESC/Tab/Enter 和小键盘 Enter 条件；Space 单独检查 DOWN/repeat 不激活、UP 单次、Tab/失焦取消及离开再返回不复活；嵌入字符回归覆盖命名文本键重复 DOWN 的高低代理拼接，以及导航/修饰命令边界清除。弹层 Tab 在一次输入内关闭并继续相邻逻辑控件，ESC 返回合法触发器。自动运行可以在真实窗口/GL 上注入明确事件以取得可复核反馈，但需区分自动路径、真实 native 事件与人工交互；不使用模拟窗口或只看启动日志替代绘制/交互验收。最终分发以自身运行库启动，同一真实闭环不借 MC 开发类路径成立。
