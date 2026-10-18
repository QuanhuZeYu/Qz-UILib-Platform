# 共享GL矢量与仿射回放

页面的路径、形状裁剪和仿射呈现遵循Core[矢量路径、仿射呈现与形状裁剪规格](<../../../Qz-UILib-Core/specs/scene/2026-10-18-uilib-vector-presentation-spec.md>)。Platform在原PaintCommand、OrderedQuadCollector和共享GL回放中消费其不可变事实，世界文字继续使用原FontRenderContext和宿主pass。

## 命令坐标与裁剪

矩形、线、路径、文字、图像与视频的输出顶点都来自命令局部分配，统一交由六参State携带transform、局部command.clip和世界shapeClip。Collector完成一次顶点映射、精确区域求交及采样坐标插值，再发布原有序批次；GL只用最终区域包络做coarse scissor，不将原局部clip当作world范围。单位矩阵与矩形路径保留旧快路。

矩阵按外层左乘组合，采用[Canvas transform矩阵示例](https://developer.mozilla.org/en-US/docs/Web/API/CanvasRenderingContext2D/transform)；形状裁剪按[Konva clipFunc多个子路径案例](https://konvajs.org/docs/clipping/Clipping_Function.html)及[Canvas clip连续求交案例](https://developer.mozilla.org/en-US/docs/Web/API/CanvasRenderingContext2D/clip)的交集含义。嵌套命令和备用片段已经由Core映射，回放scope只保存/恢复当前命令，不再次乘父矩阵，branchSource继续标识同代资源分支选择。

PATH只消费Core准备的不重叠三角mesh，每三点成为v3=v2的退化quad，走原solid/coverage材质与EBO。有效宿主pass使用STRAIGHT_RGBA时，纯PATH也声明solid白纹理需求，无需背景矩形或文字。文字需求以实际墨迹经同矩阵与精确区域判断，变换和形状外的墨迹不申请字形。world文字在caller空间裁剪后继续原齐次投影、viewport与外部scissor；旧identity入口保留原四角快路。world命令的sceneGeometry标记表示正式scene/caller几何联系，即使矩阵归一化为identity、外部矩形折回command.clip也保持；PATH工厂直接带此标记，旧RECT/LINE/TEXT/IMAGE/VIDEO工厂保持false。带此标记或显式变换/shapeClip的有限caller裁剪沿共享mesh先裁真实顶点与UV；context外部scissor只处理原viewport，UI矩形快路和原生图片direct条件保持。

## 采样与宿主图像

GlQuadRenderer逐点读取s0/t0至s3/t3。旧Quad构造仍把原u0/v0/u1/v1映成TL/TR/BR/BL，新裁剪顶点可携带独立插值UV。外部GlVertexProducer收到同一批次，亦须消费逐顶点UV，资源ID、层、generation和原draw顺序保持。

原单位矩阵、无额外shapeClip的不透明宿主图像继续直接委托。带变换或形状裁剪的宿主图像先沿原captureHost资源合成，按原image分配与局部clip确定capture面，不按world viewport提前裁掉负坐标或放大后的图像。局部原图平移到该面绘制一次，再把原capture box连同命令几何交同一collector，完成后立即提交，ready/failed/overlay不能覆盖尚未消费的临时面。预算、纹理代际、资源owner、原失败回报与scope恢复不变。

[Konva相对指针案例](https://konvajs.org/docs/sandbox/Relative_Pointer_Position.html)用当前绝对矩阵的逆把页面位置还原到局部；UILib的命中和输入映射由Core共同几何承担。Platform只消费已解析计划，不回访节点或另设焦点、输入或位置来源。

视频prepare对新变换/shapeClip以实际视频四角映射后的mesh，与effectiveClip及页面viewport精确求交，再收集原lease和纹理需求；孔内不可见帧不占预算。旧identity且无shapeClip的可见性快路、播放会话、租约和提交票据保持。

## 原场景验收

PaintPrimitiveScenario的既有--order-only入口检查共享collector仿射/局部clip/形状交集、逐顶点UV、批容量顺序及备用分支同代映射，不创建窗口。原GL入口另覆盖PATH半透明三角像素、变换纹理采样、负局部分配的宿主图像capture、单次委托、预算和后继命令状态恢复；其中独立纯PATH计划沿正式prepare与host producer检查真实white纹理及非空载荷，同一identity有限world矩形的变化UV由已有--order-only collector场景核对，原world图像不支持合同保持。原VideoGlVerification在已有真实binding/发布帧入口检查负local移入，以及孔内外帧竞争一个纹理槽的需求筛选。上述GL入口需要设备上下文，用户暂停GPU/窗口期间仅编译归档，不能以静态检查或order-only通过替代运行证据。
