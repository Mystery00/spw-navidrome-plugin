package vip.mystery0.spw.navidrome

import com.xuncorp.spw.workshop.api.*
import java.lang.reflect.Proxy

/** 独立测试进程模拟宿主注入；产品代码不得修改 WorkshopApi.instance。 */
object PermissionLifecycleTest {
 @JvmStatic fun main(args:Array<String>) {
  var warnings=0
  val manager=Proxy.newProxyInstance(WorkshopApi::class.java.classLoader,arrayOf(WorkshopApi.Manager::class.java)){_,method,values ->
   check(method.name=="isPermissionGranted"){"Denied startup must not access config or host resources"}
   check(values?.get(0)==PluginPermission.CLASS_TRANSFORM)
   false
  } as WorkshopApi.Manager
  val ui=object:WorkshopApi.Ui {
   override fun toast(text:String,type:WorkshopApi.Ui.ToastType){
    check(text.contains("允许修改应用行为与界面"))
    check(type==WorkshopApi.Ui.ToastType.Warning)
    warnings++
   }
  }
  val api=Proxy.newProxyInstance(WorkshopApi::class.java.classLoader,arrayOf(WorkshopApi::class.java)){_,method,_ ->
   when(method.name){"getManager"->manager;"getUi"->ui;else->error("Denied startup must not register hooks or playback")}
  } as WorkshopApi
  WorkshopApi.instance=api
  val plugin=NavidromePlugin(PluginContext(NavidromePlugin.ID,"test",".","1.19.2",Channel.Steam))
  repeat(2){plugin.start();plugin.stop()}
  check(warnings==2){"Each denied enable must explain how to grant permission"}
  val session=NavidromePlugin::class.java.getDeclaredField("session").apply{isAccessible=true}
  check(session.get(plugin)==null){"Denied startup must not retain a session"}
  println("PASS denied permission startup and repeated stop")
 }
}
