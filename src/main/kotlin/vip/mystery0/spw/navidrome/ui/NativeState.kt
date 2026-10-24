package vip.mystery0.spw.navidrome.ui

import androidx.compose.runtime.*
import vip.mystery0.spw.navidrome.AppSession
import vip.mystery0.spw.navidrome.credentials.CredentialStore
import vip.mystery0.spw.navidrome.model.Models
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** 页面状态与 IO 分离；每次切换丢弃前一页面的迟到响应。 */
class NativeState(val session: AppSession, private val store: CredentialStore): AutoCloseable {
 var address by mutableStateOf("")
 var username by mutableStateOf("")
 var password by mutableStateOf("")
 var allowHttp by mutableStateOf(false)
 var query by mutableStateOf("")
 var error by mutableStateOf(false)
 var credentialError by mutableStateOf(false)
 var accountExpanded by mutableStateOf(false)
 var restoring by mutableStateOf(true)
 var credentialStatus by mutableStateOf("Windows 当前用户加密保存，重启后自动连接。")
 var selectionTitle by mutableStateOf("选择专辑查看歌曲")
 var selectedCollectionId by mutableStateOf("")
 val showAccount:Boolean get() = !restoring && (!connected || accountExpanded)
 var status by mutableStateOf("请输入服务器和账号，成功登录后使用 Windows 加密保存。")
 var busy by mutableStateOf(false)
 var connected by mutableStateOf(false)
 var mode by mutableStateOf("albums")
 var collections by mutableStateOf<List<Models.Collection>>(emptyList())
 var songs by mutableStateOf<List<Models.Song>>(emptyList())
 var selected by mutableStateOf(-1)
 var more by mutableStateOf(false)
 private var offset=0
 private var searchTerm=""
 private var playlists: List<Models.Collection> = emptyList()
 private val requests=ViewRequest()
 private var request: Future<*>?=null
 private val account=Any()
 private var generation=0L
 private var pendingSecret:CharArray?=null
 private var closed=false
 private val secrets=Executors.newSingleThreadExecutor { r -> Thread(r,"navidrome-credentials").apply { isDaemon=true } }
 init {
  session.onNotice { if(it!=session.compatibility())status=it }
  val initial=synchronized(account){ generation }
  secrets.execute {
   try { store.load().ifPresent { saved -> saved.use {
    synchronized(account) {
     if(!closed && generation==initial) {
      address=it.address(); username=it.username(); allowHttp=it.allowHttp()
      login(it.password(),false)
     }
    }
   } } } catch(e:Exception) { synchronized(account){if(!closed && generation==initial){error=true;credentialError=true;status="无法恢复加密凭据，请重新登录。";credentialStatus="加密凭据恢复失败，请重新输入账号。"}} }
   finally {synchronized(account){if(!closed && !busy)restoring=false}}
  }
 }
 private fun invalidate():Long { request?.cancel(true); busy=false; return requests.begin() }
 fun login() { val secret=password.toCharArray(); password=""; login(secret,true) }
 private fun login(secret:CharArray,save:Boolean) {
  val token=synchronized(account){pendingSecret?.fill('\u0000');pendingSecret=null;++generation}
  error=false;credentialError=false;invalidate(); collections=emptyList(); songs=emptyList(); connected=false; busy=true;status="正在连接…"
  val a=address; val u=username; val h=allowHttp; val retained=secret.clone();synchronized(account){pendingSecret=retained}
  session.connect(a,u,secret,h,{ message ->
   synchronized(account) {
    if(closed || generation!=token){retained.fill('\u0000');return@connect}
    connected=true; restoring=false;accountExpanded=false;busy=false;status=message
    credentialStatus=if(save)"正在加密保存登录凭据…" else "已恢复此设备保存的账号。"
    if(save) secrets.execute {
     try {
      val input=synchronized(account){if(!closed && generation==token)retained.clone()else null}
      if(input!=null)try{store.save(a,u,input,h);synchronized(account){if(!closed&&generation==token)credentialStatus="登录凭据已使用 Windows 加密保存。"}}finally{input.fill('\u0000')}
     }
     catch(e:Exception){synchronized(account){if(!closed&&generation==token){credentialError=true;credentialStatus="加密凭据保存失败；旧凭据仍保留。"}}}
     finally {retained.fill('\u0000')}
    } else retained.fill('\u0000')
    browse("albums")
   }
  },{message -> retained.fill('\u0000'); synchronized(account){if(!closed&&generation==token){busy=false;restoring=false;accountExpanded=true;error=true;status=message}} })
 }
 fun logout() {
  val token=synchronized(account){++generation;pendingSecret?.fill('\u0000');pendingSecret=null;session.disconnect();connected=false;restoring=false;accountExpanded=true;credentialError=false;error=false;credentialStatus="正在忘记此设备的登录凭据…";password="";invalidate();collections=emptyList();songs=emptyList();selected=-1;more=false;generation}
  secrets.execute { try {store.clear();synchronized(account){if(!closed&&generation==token){credentialStatus="已忘记此设备的登录凭据。";status="已退出，请重新连接服务器。"}}}catch(e:Exception){synchronized(account){if(!closed&&generation==token){credentialError=true;credentialStatus="凭据删除失败，请检查磁盘权限。"}}} }
 }
 private fun <T> load(work:AppSession.Request<T>,apply:(T)->Unit) {
  error=false;val token=invalidate();more=false;busy=true;status="正在加载…"
  request=session.request(work,{value -> if(requests.accept(token)){busy=false;apply(value)}},{error -> if(requests.accept(token)){busy=false;this.error=true;status=error}})
 }
 fun browse(target:String,append:Boolean=false) {
  if(!connected){status="请先连接服务器";return}
  if(!append){mode=target;selectionTitle=if(target=="albums")"选择专辑查看歌曲" else "选择歌单查看歌曲";selectedCollectionId="";offset=0;collections=emptyList();songs=emptyList();selected=-1}
  if(target=="playlists" && append){showPlaylistPage();return}
  val start=offset
  load(AppSession.Request { c -> if(target=="albums")c.albums(start) else c.playlists() }) { rows ->
   if(target=="playlists"){playlists=rows;showPlaylistPage()}else{collections=collections+rows;offset=start+rows.size;more=rows.size==50}
   status="已加载 ${collections.size} 项；选择专辑或歌单查看歌曲。"
  }
 }
 private fun showPlaylistPage(){val end=minOf(offset+50,playlists.size);collections=collections+playlists.subList(offset,end);offset=end;more=end<playlists.size}
 fun open(item:Models.Collection){val previousMore=more;selectionTitle=item.name();selectedCollectionId=item.id();selected=-1;songs=emptyList();val playlist=mode=="playlists";load(AppSession.Request {c -> if(playlist)c.playlist(item.id()) else c.album(item.id())}){songs=it;more=previousMore;status="${item.name()} · ${it.size} 首"}}
 fun search(append:Boolean=false){
  if(!connected){status="请先连接服务器";return}
  if(!append){searchTerm=query.trim();if(searchTerm.isEmpty()){error=true;status="请输入搜索词";return};invalidate();mode="search";selectionTitle="搜索：$searchTerm";selectedCollectionId="";offset=0;collections=emptyList();songs=emptyList();selected=-1}
  val start=offset;val term=searchTerm
  load(AppSession.Request {c -> c.search(term,start)}){songs=songs+it;offset=start+it.size;more=it.size==50;status="已加载 ${songs.size} 首歌曲"}
 }
 fun next(){if(mode=="search")search(true) else browse(mode,true)}
 fun play(){if(selected !in songs.indices){status="请先选择歌曲";return};session.play(songs,selected){status=it}}
 override fun close(){synchronized(account){closed=true;++generation;pendingSecret?.fill('\u0000');pendingSecret=null;requests.close();request?.cancel(true);password="";session.onNotice { }};secrets.shutdown()}
}
