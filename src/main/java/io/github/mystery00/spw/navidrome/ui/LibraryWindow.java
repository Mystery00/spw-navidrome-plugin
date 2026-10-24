package io.github.mystery00.spw.navidrome.ui;

import io.github.mystery00.spw.navidrome.AppSession;
import io.github.mystery00.spw.navidrome.model.Models;
import io.github.mystery00.spw.navidrome.net.SubsonicClient;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.*;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

/** 插件专属管理窗口，播放完全交给宿主；所有控件状态只在 EDT 更新。 */
public final class LibraryWindow {
    private final AppSession session;
    private final JFrame frame=new JFrame("SPW Navidrome");
    private final JTextField address=new JTextField(26),username=new JTextField(12),query=new JTextField(22);
    private final JPasswordField password=new JPasswordField(12);
    private final JCheckBox http=new JCheckBox("明确允许 HTTP（局域网）");
    private final JLabel status=new JLabel("请连接服务器。密码仅保存在本次会话中。"),info=new JLabel(),cover=new JLabel("暂无封面",SwingConstants.CENTER);
    private final DefaultListModel<Models.Collection> collections=new DefaultListModel<>();
    private final JList<Models.Collection> list=new JList<>(collections);
    private final SongTable model=new SongTable();
    private final JTable table=new JTable(model);
    private final JButton more=new JButton("继续加载"),connect=new JButton("连接测试并登录"),retry=new JButton("重试");
    private Future<?> request,coverRequest;
    private long view,coverView;
    private String mode="albums",searchQuery="";
    private int offset;
    private Runnable retryAction=()->{};
    private boolean disposed;
    public LibraryWindow(AppSession session){
        this.session=session;
        frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);frame.setMinimumSize(new Dimension(900,650));frame.setSize(1120,780);frame.setLocationByPlatform(true);
        JPanel root=new JPanel(new BorderLayout(10,10));root.setBorder(BorderFactory.createEmptyBorder(14,14,14,14));frame.setContentPane(root);
        JPanel top=new JPanel();top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS));
        JPanel login=new JPanel(new FlowLayout(FlowLayout.LEFT));login.add(new JLabel("服务器"));login.add(address);login.add(new JLabel("用户名"));login.add(username);login.add(new JLabel("密码"));login.add(password);top.add(login);
        JPanel account=new JPanel(new FlowLayout(FlowLayout.LEFT));account.add(http);account.add(connect);button(account,"退出 / 切换账户",this::logout);account.add(new JLabel("凭据仅会话保存；关闭窗口不退出，禁用插件会清除"));top.add(account);
        JPanel toolbar=new JPanel(new FlowLayout(FlowLayout.LEFT));button(toolbar,"专辑",()->loadCollections("albums",false));button(toolbar,"歌单",()->loadCollections("playlists",false));toolbar.add(query);button(toolbar,"搜索歌曲",()->search(false));query.addActionListener(e->search(false));toolbar.add(more);more.setEnabled(false);more.addActionListener(e->{if(mode.equals("search"))search(true);else loadCollections(mode,true);});button(toolbar,"清理音频缓存",()->session.clearCache(this::message));top.add(toolbar);
        info.setText(session.compatibility()+"；缓存上限 512 MiB");top.add(info);root.add(top,BorderLayout.NORTH);
        table.setRowHeight(30);table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.setAutoCreateRowSorter(false);
        table.addMouseListener(new MouseAdapter(){@Override public void mouseClicked(MouseEvent e){if(e.getClickCount()==2)play();}});
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ENTER"),"play");table.getActionMap().put("play",new AbstractAction(){public void actionPerformed(ActionEvent e){play();}});
        table.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting())showCover();});
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);list.addListSelectionListener(e->{if(!e.getValueIsAdjusting() && list.getSelectedValue()!=null)openCollection(list.getSelectedValue());});
        JPanel left=new JPanel(new BorderLayout());left.add(new JScrollPane(list),BorderLayout.CENTER);cover.setPreferredSize(new Dimension(220,220));left.add(cover,BorderLayout.SOUTH);
        JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,left,new JScrollPane(table));split.setDividerLocation(300);root.add(split,BorderLayout.CENTER);
        JPanel footer=new JPanel(new BorderLayout());JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT));button(actions,"从选中歌曲开始播放",this::play);retry.setEnabled(false);retry.addActionListener(e->retryAction.run());actions.add(retry);footer.add(actions,BorderLayout.NORTH);footer.add(status,BorderLayout.SOUTH);root.add(footer,BorderLayout.SOUTH);
        connect.addActionListener(e->login());password.addActionListener(e->login());session.onNotice(this::message);
    }
    private static void button(JPanel panel,String text,Runnable action){JButton b=new JButton(text);b.addActionListener(e->action.run());panel.add(b);}
    public void show(){if(!disposed){frame.setVisible(true);frame.toFront();}}
    public void dispose(){disposed=true;invalidate();password.setText("");frame.dispose();}
    private void message(String text){status.setText(text);status.setToolTipText(text);info.setText(session.compatibility()+"；音频缓存 "+session.cacheBytes()/1024/1024+" / 512 MiB");}
    private long invalidate(){view++;coverView++;if(request!=null)request.cancel(true);if(coverRequest!=null)coverRequest.cancel(true);return view;}
    private void login(){
        invalidate();model.set(List.of());collections.clear();cover.setIcon(null);connect.setEnabled(false);more.setEnabled(false);retry.setEnabled(false);message("正在连接…");
        char[] secret=password.getPassword();password.setText("");
        session.connect(address.getText(),username.getText(),secret,http.isSelected(),ok->{connect.setEnabled(true);message(ok);loadCollections("albums",false);},error->{connect.setEnabled(true);message(error);});
    }
    private void logout(){invalidate();session.disconnect();password.setText("");connect.setEnabled(true);collections.clear();model.set(List.of());cover.setIcon(null);cover.setText("暂无封面");more.setEnabled(false);retry.setEnabled(false);message("已退出；旧请求和播放地址已失效，请输入新账户连接");}
    private <T> void load(AppSession.Request<T> work,java.util.function.Consumer<T> success,Runnable again){
        long generation=invalidate();retryAction=again;retry.setEnabled(false);more.setEnabled(false);message("正在加载…");
        request=session.request(work,value->{if(disposed||generation!=view)return;success.accept(value);},error->{if(disposed||generation!=view)return;message(error);retry.setEnabled(true);});
    }
    private void loadCollections(String target,boolean append){
        if(!session.connected()){message("请先连接服务器");return;}
        mode=target;if(!append){offset=0;collections.clear();model.set(List.of());}
        int pageOffset=offset;
        load(c->target.equals("albums")?c.albums(pageOffset):c.playlists(),rows->{
            rows.forEach(collections::addElement);offset=pageOffset+rows.size();more.setEnabled(target.equals("albums")&&rows.size()==SubsonicClient.PAGE_SIZE);
            message(rows.isEmpty()?"没有更多结果":"已加载 "+collections.size()+" 项；单击左侧专辑或歌单查看歌曲");
        },()->loadCollections(target,append));
    }
    private void openCollection(Models.Collection item){
        boolean playlist=mode.equals("playlists");model.set(List.of());
        load(c->playlist?c.playlist(item.id()):c.album(item.id()),songs->{model.set(songs);message(songs.isEmpty()?"此列表没有歌曲":item.name()+" · "+songs.size()+" 首；双击歌曲开始连续播放");more.setEnabled(mode.equals("albums")&&offset>0&&offset%50==0);},()->openCollection(item));
    }
    private void search(boolean append){
        if(!session.connected()){message("请先连接服务器");return;}
        if(!append){searchQuery=query.getText().trim();if(searchQuery.isEmpty()){message("请输入搜索词");return;}mode="search";offset=0;collections.clear();model.set(List.of());}
        int pageOffset=offset;String q=searchQuery;
        load(c->c.search(q,pageOffset),songs->{List<Models.Song> all=new ArrayList<>(model.songs);all.addAll(songs);model.set(all);offset=pageOffset+songs.size();more.setEnabled(songs.size()==50);message(all.isEmpty()?"没有搜索结果":"已加载 "+all.size()+" 首歌曲");},()->search(append));
    }
    private void play(){int row=table.getSelectedRow();if(row<0){message("请先选择一首歌曲");return;}session.play(model.songs,row,this::message);}
    private void showCover(){
        long token=++coverView;if(coverRequest!=null)coverRequest.cancel(true);cover.setIcon(null);cover.setText("暂无封面");int row=table.getSelectedRow();
        if(row<0||row>=model.songs.size())return;String id=model.songs.get(row).coverId();if(id.isBlank())return;
        coverRequest=session.request(c->{byte[] b=c.cover(id);try(var in=new ByteArrayInputStream(b)){var img=ImageIO.read(in);return img==null?null:new ImageIcon(img.getScaledInstance(192,192,Image.SCALE_SMOOTH));}},image->{if(token==coverView&&!disposed){cover.setIcon(image);cover.setText(image==null?"暂无封面":"");}},error->{if(token==coverView)cover.setText("封面加载失败");});
    }
    private static final class SongTable extends AbstractTableModel {
        private List<Models.Song> songs=List.of();
        void set(List<Models.Song> items){songs=List.copyOf(items);fireTableDataChanged();}
        @Override public int getRowCount(){return songs.size();}
        @Override public int getColumnCount(){return 4;}
        @Override public String getColumnName(int c){return new String[]{"标题","艺术家","专辑","时长"}[c];}
        @Override public Object getValueAt(int r,int c){var s=songs.get(r);return switch(c){case 0->s.title();case 1->s.artist();case 2->s.album();default->String.format("%d:%02d",s.durationMs()/60000,s.durationMs()/1000%60);};}
    }
}
