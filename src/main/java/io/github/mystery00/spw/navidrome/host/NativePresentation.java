package io.github.mystery00.spw.navidrome.host;

import java.lang.reflect.*;

/** Steam 1.18.5 封面所有权与歌词发射器适配；仅由串行宿主线程调用。 */
final class NativePresentation {
    @FunctionalInterface interface Current { boolean check() throws Exception; }
    private final ClassLoader loader;
    private final Object service,monitor,cache,lock;
    private final Field imageOwner;
    private final Class<?> ownerType,monitorType;
    private final Method sourceChanged,updateImage;
    private Object ours,ourDocument;
    final class Prepared implements AutoCloseable {
        Object owner;final Object document;
        Prepared(Object owner,Object document){this.owner=owner;this.document=document;}
        public void close()throws Exception{if(owner!=null){((AutoCloseable)owner).close();owner=null;}}
    }
    NativePresentation(ClassLoader loader,Object service) throws Exception {
        this.loader=loader;this.service=service;
        ownerType=type("com.xuncorp.voxzen.image.RefCountedImageBitmap");
        monitorType=type("com.xuncorp.voxzen.service.PlaybackMonitor");
        monitor=monitorType.getField("INSTANCE").get(null);
        cache=type("com.xuncorp.voxzen.image.ScaledImageBitmapCacheKt").getMethod("getSharedScaledImageBitmapCache").invoke(null);
        sourceChanged=cache.getClass().getMethod("onSourceChanged",ownerType);
        updateImage=type("com.xuncorp.voxzen.service.PlaybackMonitorKt").getMethod("updateImageBitmap",monitorType,ownerType);
        imageOwner=field("imageBitmapOwner");lock=field("imageBitmapOwnerLock").get(service);
    }
    private Class<?> type(String name)throws Exception{return Class.forName(name,false,loader);}
    private Field field(String name)throws Exception{Field f=service.getClass().getDeclaredField(name);f.setAccessible(true);return f;}
    Prepared prepare(byte[] cover,String lyrics)throws Exception {
        Object owner=null;
        if(cover!=null) {
            Class<?> util=type("com.xuncorp.voxzen.util.ImageBitmapUtil");
            Object image=util.getMethod("decodeToExactSize",byte[].class,int.class,int.class,boolean.class)
                    .invoke(util.getField("INSTANCE").get(null),cover,512,512,false);
            if(image!=null) {
                Object bitmap=type("androidx.compose.ui.graphics.SkiaImageAsset_skikoKt")
                        .getMethod("asSkiaBitmap",type("androidx.compose.ui.graphics.ImageBitmap")).invoke(null,image);
                owner=ownerType.getConstructor(type("org.jetbrains.skia.Bitmap")).newInstance(bitmap);
            }
        }
        try {
            Class<?> document=type("com.xuncorp.spc.lyrics.\u0528");
            Object parsed=lyrics==null || lyrics.isBlank()?document.getMethod("\u052c").invoke(null)
                    :type("androidx.compose.ui.\u1021").getMethod("\u037f",String.class).invoke(null,lyrics);
            return new Prepared(owner,parsed);
        }catch(Exception|LinkageError e){if(owner!=null)((AutoCloseable)owner).close();throw e;}
    }
    boolean publish(Prepared prepared,Current current)throws Exception {
        try {
            synchronized(lock) {
                if(!current.check())return false;
                Object previous=imageOwner.get(service);
                imageOwner.set(service,prepared.owner);ours=prepared.owner;prepared.owner=null;
                sourceChanged.invoke(cache,ours);updateImage.invoke(null,monitor,ours);
                if(previous!=null && previous!=ours)((AutoCloseable)previous).close();
            }
            if(!current.check())return false;
            applyLyrics(prepared.document);ourDocument=prepared.document;
            System.out.println("[SPW Navidrome] Native metadata applied: cover="+(ours!=null));
            return true;
        } finally {prepared.close();}
    }
    private void applyLyrics(Object parsed)throws Exception {
        Class<?> document=type("com.xuncorp.spc.lyrics.\u0528");
        Object emitter=field("lyricsEmitter").get(service);Class<?> emitterType=emitter.getClass();
        emitterType.getMethod("\u0528").invoke(emitter);
        monitorType.getMethod("updateLyricsDocument$Voxzen_composeApp",document).invoke(monitor,parsed);
        emitterType.getMethod("\u037f",document).invoke(emitter,parsed);
        emitterType.getMethod("\u037f",emitterType).invoke(null,emitter);
    }
    void clearOwned()throws Exception {
        synchronized(lock) {
            if(ours!=null && imageOwner.get(service)==ours) {
                imageOwner.set(service,null);sourceChanged.invoke(cache,new Object[]{null});
                updateImage.invoke(null,monitor,null);((AutoCloseable)ours).close();
            }
            ours=null;
        }
        Object flow=monitorType.getMethod("getLyricsDocument").invoke(monitor);
        Object current=type("kotlinx.coroutines.flow.StateFlow").getMethod("getValue").invoke(flow);
        if(ourDocument!=null && current==ourDocument)applyLyrics(type("com.xuncorp.spc.lyrics.\u0528").getMethod("\u052c").invoke(null));
        ourDocument=null;
    }
}
