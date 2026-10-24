@file:OptIn(androidx.compose.runtime.InternalComposeApi::class)
package vip.mystery0.spw.navidrome.ui

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import java.lang.reflect.Method

/** 所有页面语义色来自当前宿主 SaltTheme；派生色在当前表面合成。 */
internal data class NativePalette(val dark:Boolean,val highlight:Color,val text:Color,val subText:Color,val background:Color,val surface:Color,val border:Color,val onHighlight:Color,val success:Color,val warning:Color,val error:Color) {
 val selected:Color get()=highlight.copy(alpha=if(dark).24f else .13f).compositeOver(surface)
 val disabled:Color get()=text.copy(alpha=.06f).compositeOver(surface)
 val input:Color get()=text.copy(alpha=.025f).compositeOver(surface)
 val warningBackground:Color get()=warning.copy(alpha=if(dark).16f else .10f).compositeOver(surface)
}
internal val LocalNativePalette=staticCompositionLocalOf<NativePalette>{error("Host theme is not available")}

/** 宿主 1.19.2 实际 DLL 已核实的 SaltTheme ABI；不使用系统主题替代宿主选择。 */
internal object HostTheme {
 private val names=listOf("Ϳ","Ԩ","ԩ","Ԫ","ԫ","Ԭ","ԭ","Ԯ","ԯ","ՠ","ֈ")
 private class Reader(val configs:CompositionLocal<Any?>,val colors:CompositionLocal<Any?>,val dark:Method,val lightColors:Method,val darkColors:Method,val getters:List<Method>){
  fun read(composer:Composer):NativePalette {
   val config=checkNotNull(composer.consume(configs))
   val dynamic=checkNotNull(composer.consume(colors))
   val isDark=dark.invoke(config) as Boolean
   val palette=(if(isDark)darkColors else lightColors).invoke(dynamic)
   val c=getters.map {colorFromPacked(it.invoke(palette) as Long)}
   return NativePalette(isDark,c[0],c[1],c[2],c[3],c[4],c[6],c[7],c[8],c[9],c[10])
  }
 }
 private val reader:Reader by lazy {
  val loader=HostTheme::class.java.classLoader
  val theme=Class.forName("androidx.compose.ui.ʶ",false,loader)
  val configs=Class.forName("androidx.compose.ui.ˤ",false,loader)
  val dynamic=Class.forName("androidx.compose.ui.ʴ",false,loader)
  val colors=Class.forName("androidx.compose.ui.ʹ",false,loader)
  @Suppress("UNCHECKED_CAST")
  fun local(name:String)=theme.getMethod(name).invoke(null) as CompositionLocal<Any?>
  Reader(local("Ϳ"),local("Ԩ"),configs.getMethod("Ϳ"),dynamic.getMethod("Ϳ"),dynamic.getMethod("Ԩ"),names.map{colors.getMethod(it)})
 }
 private var reported=false
 fun validate(){reader}
 // 宿主已经返回 Color 的打包位，必须保留 ULong 位模式，不能再走 ARGB Long 工厂。
 fun colorFromPacked(packed:Long):Color=Color(packed.toULong())
 @Composable fun read():NativePalette? {
  val composer=currentComposer
  return try{reader.read(composer)}catch(t:Throwable){
   if(!reported){reported=true;System.err.println("[SPW Navidrome] Host theme read failed: "+t.javaClass.simpleName)}
   null
  }
 }
}
