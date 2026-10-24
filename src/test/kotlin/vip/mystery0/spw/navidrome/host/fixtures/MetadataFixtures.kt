package vip.mystery0.spw.navidrome.host.fixtures

/** 编译后由测试工具改写 JVM 名称，Metadata 保留原始语义。 */
class MetadataColors(
    val highlight: Long,
    val text: Long,
    val subText: Long,
    val background: Long,
    val surface: Long,
    val alternate: Long,
    val border: Long,
    val onHighlight: Long,
    val success: Long,
    val warning: Long,
    val error: Long,
)

/** 两个同类型属性不能仅凭字段类型或反射枚举顺序区分。 */
class MetadataDynamicColors(val light: MetadataColors, val dark: MetadataColors)

/** 与目标签名不同的干扰类型，不得被颜色解析器误认领。 */
class MetadataDistractor(val highlight: String, val text: Long)
