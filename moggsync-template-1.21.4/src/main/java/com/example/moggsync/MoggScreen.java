package com.example.moggsync;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

public class MoggScreen extends Screen {

    private static final int W=452,H=356,PAD=16,ROW=21,FW=190;
    private static final String[] TABS={"Основное","Ритуал","Лобби","Сессия","HUD","Telegram"};
    private static final int
        C_PNL=0xFF13151F,C_HDR=0xFF181A28,C_CARD=0xFF1C1F2E,C_BOR=0xFF282B3F,
        C_ACC=0xFF7C5CFF,C_GLW=0xFF9D7FFF,C_GRN=0xFF2FBF71,C_RED=0xFFD64545,
        C_ORG=0xFFFF9038,C_TXT=0xFFCDD5F0,C_DIM=0xFF6870A0,C_WHT=0xFFEEF2FF;

    private final MoggSyncClient mod;
    private final MoggConfig cfg;
    private int tab=0,L,T;
    private long openTime=-1;
    private String shownCid="";
    private final List<int[]> lbls=new ArrayList<>();  // [x,y,color,textIdx]
    private final List<String> lblTxt=new ArrayList<>();

    // command-queue editor state
    private String newJoinCmd="", newKeyword="";

    public MoggScreen(){super(Text.literal("MoggSync"));mod=MoggSyncClient.get();cfg=mod.cfg();}

    // ===== FlatButton =====
    private static class FB extends ButtonWidget{
        int c;
        FB(int x,int y,int w,int h,Text t,PressAction a,int c){super(x,y,w,h,t,a,DEFAULT_NARRATION_SUPPLIER);this.c=c;}
        void col(int nc){c=nc;}
        @Override public void renderWidget(DrawContext ctx,int mx,int my,float d){
            int x=getX(),y=getY(),w=getWidth(),h=getHeight(),bg=isHovered()?br(c,28):c;
            ctx.fill(x,y,x+w,y+h,bg);ctx.fill(x,y+h-1,x+w,y+h,0x28000000);ctx.fill(x,y,x+w,y+1,0x18FFFFFF);
            ctx.drawCenteredTextWithShadow(MinecraftClient.getInstance().textRenderer,getMessage(),x+w/2,y+(h-8)/2,C_WHT);
        }
        static int br(int c,int a){return 0xFF000000|(Math.min(255,((c>>16)&255)+a)<<16)|(Math.min(255,((c>>8)&255)+a)<<8)|Math.min(255,(c&255)+a);}
    }
    private static int lerp(int a,int b,float t){
        return 0xFF000000|((((a>>16)&255)+(int)(t*(((b>>16)&255)-((a>>16)&255))))<<16)|((((a>>8)&255)+(int)(t*(((b>>8)&255)-((a>>8)&255))))<<8)|(((a&255)+(int)(t*((b&255)-(a&255)))));}
    private static int al(int c,int a){return(a<<24)|(c&0xFFFFFF);}

    // ===== Init =====
    @Override protected void init(){
        if(openTime<0)openTime=System.currentTimeMillis();
        L=(width-W)/2;T=(height-H)/2;
        lbls.clear();lblTxt.clear();
        shownCid=cfg.CHAT_ID;newJoinCmd="";newKeyword="";

        // Master button
        final FB[] mb={null};boolean on=mod.isEnabled();
        mb[0]=new FB(L+W-PAD-120,T+8,120,26,Text.literal(on?"● МОД: ВКЛ":"○ МОД: ВЫКЛ"),b->{
            boolean v=!mod.isEnabled();mod.setEnabled(v);
            mb[0].setMessage(Text.literal(v?"● МОД: ВКЛ":"○ МОД: ВЫКЛ"));mb[0].col(v?C_GRN:C_RED);
        },on?C_GRN:C_RED);addDrawableChild(mb[0]);

        // Tabs
        int tw=(W-PAD*2-(TABS.length-1)*3)/TABS.length;
        for(int i=0;i<TABS.length;i++){final int idx=i;
            addDrawableChild(new FB(L+PAD+i*(tw+3),T+48,tw,19,Text.literal(TABS[i]),b->{tab=idx;clearAndInit();},i==tab?C_ACC:C_BOR));}

        int cy=T+98;
        switch(tab){case 0->bMain(cy);case 1->bRitual(cy);case 2->bLobby(cy);case 3->bSession(cy);case 4->bHud(cy);default->bTelegram(cy);}

        addDrawableChild(new FB(L+W-PAD-110,T+H-32,110,22,Text.literal("Готово"),b->close(),C_GRN));
    }

    // ===== Helpers =====
    private int fx(){return L+W-PAD-FW;}
    private void lbl(String t,int y){lbls.add(new int[]{L+PAD,y+5,C_TXT,lblTxt.size()});lblTxt.add(t);}
    private void note(String t,int y){lbls.add(new int[]{L+PAD,y,C_DIM,lblTxt.size()});lblTxt.add(t);}
    private void fld(int y,String l,String v,int mx,boolean num,Consumer<String> cb){
        lbl(l,y);var f=new TextFieldWidget(textRenderer,fx(),y,FW,18,Text.literal(l));
        f.setMaxLength(mx);if(num)f.setTextPredicate(s->s.matches("\\d*"));f.setText(v==null?"":v);f.setChangedListener(cb);addDrawableChild(f);}
    private void num(int y,String l,int v,int mn,IntConsumer s){fld(y,l,String.valueOf(v),6,true,t->{if(!t.isEmpty())try{s.accept(Math.max(mn,Integer.parseInt(t)));}catch(NumberFormatException ignored){}});}
    private void tog(int y,String l,Supplier<Boolean> g,Consumer<Boolean> s){
        lbl(l,y);final FB[] r={null};boolean on=g.get();
        r[0]=new FB(fx(),y,FW,18,Text.literal(on?"ВКЛ":"ВЫКЛ"),b->{boolean v=!g.get();s.accept(v);r[0].setMessage(Text.literal(v?"ВКЛ":"ВЫКЛ"));r[0].col(v?C_GRN:C_RED);},on?C_GRN:C_RED);
        addDrawableChild(r[0]);}
    private FB btn(int x,int y,int w,String t,Runnable r){return addDrawableChild(new FB(x,y,w,19,Text.literal(t),b->r.run(),C_BOR));}
    private static String fmt(long n){if(n>=1_000_000)return String.format("%.1fм",n/1_000_000.0);if(n>=1000)return String.format("%.1fк",n/1000.0);return String.valueOf(n);}

    // ===== Tabs =====
    private void bMain(int y){
        tog(y,"Авто-включение при запуске",()->cfg.enabledOnStart,v->cfg.enabledOnStart=v);y+=ROW;
        fld(y,"Только на аккаунтах (Ник1,Ник2)",cfg.activeAccounts,80,false,s->cfg.activeAccounts=s.strip());y+=ROW;
        fld(y,"Ник напарника",cfg.partnerName,16,false,s->cfg.partnerName=s.strip());y+=ROW;
        fld(y,"Фраза-приглашение в чат",cfg.readyPhrase,64,false,s->cfg.readyPhrase=s);y+=ROW;
        fld(y,"Пароль от аккаунта",cfg.password,64,false,s->cfg.password=s);y+=ROW;
        tog(y,"Авто-ввод пароля (LOGIN экран)",()->cfg.autoLogin,v->cfg.autoLogin=v);y+=ROW;
        tog(y,"Авто-возрождение при смерти",()->cfg.autoRespawn,v->cfg.autoRespawn=v);y+=ROW;
        fld(y,"Телепорт после смерти",cfg.deathTeleportCmd,40,false,s->cfg.deathTeleportCmd=s.strip());y+=ROW;
        tog(y,"Авто-переподключение",()->cfg.autoReconnect,v->cfg.autoReconnect=v);y+=ROW;
        note("Пароль — только в config/moggsync.json, в чат не уходит.",y+3);
        note("Пусто в аккаунтах = мод работает на любом аккаунте.",y+14);}

    private void bRitual(int y){
        num(y,"Кд от (сек)",cfg.timerMinSeconds,1,v->cfg.timerMinSeconds=v);y+=ROW;
        num(y,"Кд до (сек)",cfg.timerMaxSeconds,1,v->cfg.timerMaxSeconds=v);y+=ROW;
        num(y,"Повтор приглашения (сек)",cfg.retrySeconds,5,v->cfg.retrySeconds=v);y+=ROW;
        num(y,"Ответ напарнику от (мс)",cfg.replyMinMs,0,v->cfg.replyMinMs=v);y+=ROW;
        num(y,"Ответ напарнику до (мс)",cfg.replyMaxMs,0,v->cfg.replyMaxMs=v);y+=ROW;
        num(y,"Пауза перед кликом меню (мс)",cfg.menuClickDelayMs,0,v->cfg.menuClickDelayMs=v);y+=ROW;
        num(y,"Шифт: переключать каждые (мс)",cfg.danceToggleMs,50,v->cfg.danceToggleMs=v);y+=ROW;
        num(y,"Таймаут танца (сек)",cfg.danceTimeoutSeconds,5,v->cfg.danceTimeoutSeconds=v);y+=ROW;
        note("Кд 300-480 = 5-8 мин. Шифт 250 = 2 раза/сек.",y+3);}

    private void bLobby(int y){
        tog(y,"Авто-вход через лобби",()->cfg.autoLobby,v->cfg.autoLobby=v);y+=ROW;
        fld(y,"Команда лобби (/reallyworld)",cfg.lobbyCommand,40,false,s->cfg.lobbyCommand=s.strip());y+=ROW;
        MoggConfig.MenuStep st=mod.lastStep();
        fld(y,"Предмет в меню (название)",st.item,40,false,s->mod.lastStep().item=s.strip());y+=ROW;
        fld(y,"Команда фарма после входа",cfg.farmCommand,40,false,s->cfg.farmCommand=s.strip());y+=ROW;

        // Command queue
        lbls.add(new int[]{L+PAD,y+5,0xFFFFCC66,lblTxt.size()});lblTxt.add("Очередь команд при входе:");y+=ROW;
        List<String> cmds=cfg.joinCommands==null?new ArrayList<>():cfg.joinCommands;
        if(cmds.isEmpty()){note("(пусто — добавьте команды ниже)",y+2);y+=ROW-4;}
        else{for(int i=0;i<Math.min(cmds.size(),4);i++){note((i+1)+". "+cmds.get(i),y+2);y+=12;} if(cmds.size()>4)note("...ещё "+(cmds.size()-4)+" в moggsync.json",y+2);}
        y+=4;
        // Input + Add button
        var inp=new TextFieldWidget(textRenderer,L+PAD,y,FW,18,Text.literal("Команда"));
        inp.setMaxLength(60);inp.setText(newJoinCmd);
        inp.setChangedListener(s->newJoinCmd=s);
        addDrawableChild(inp);
        btn(L+PAD+FW+4,y,50,"Добавить",()->{
            if(!newJoinCmd.isBlank()){
                if(cfg.joinCommands==null)cfg.joinCommands=new ArrayList<>();
                cfg.joinCommands=new ArrayList<>(cfg.joinCommands);
                cfg.joinCommands.add(newJoinCmd.strip());
                MoggConfig.save(cfg);clearAndInit();}});y+=ROW+2;
        btn(L+PAD,y,80,"Очистить всё",()->{cfg.joinCommands=new ArrayList<>();MoggConfig.save(cfg);clearAndInit();});
        num(y,"+задержка мс",cfg.joinCommandDelayMs,100,v->cfg.joinCommandDelayMs=v);y+=ROW;
        btn(L+PAD,T+H-32,130,"Тест входа",()->{close();mod.startLobbyTest();});}

    private void bSession(int y){
        tog(y,"Авто-отчёт в Telegram в полночь",()->cfg.sessionReportAtMidnight,v->cfg.sessionReportAtMidnight=v);y+=ROW;
        tog(y,"Уведомление о смерти в Telegram",()->cfg.notifyDeathTg,v->cfg.notifyDeathTg=v);y+=ROW;

        lbls.add(new int[]{L+PAD,y+5,0xFFFFCC66,lblTxt.size()});lblTxt.add("Алерты по ключевым словам:");y+=ROW;
        List<String> kws=cfg.alertKeywords==null?new ArrayList<>():cfg.alertKeywords;
        if(kws.isEmpty()){note("(пусто — мод не следит за словами)",y+2);y+=ROW-4;}
        else{for(int i=0;i<Math.min(kws.size(),4);i++){note("• "+kws.get(i),y+2);y+=12;} if(kws.size()>4)note("...ещё "+(kws.size()-4)+" в moggsync.json",y+2);}
        y+=4;
        var ki=new TextFieldWidget(textRenderer,L+PAD,y,FW,18,Text.literal("Ключевое слово"));
        ki.setMaxLength(32);ki.setText(newKeyword);ki.setChangedListener(s->newKeyword=s);
        addDrawableChild(ki);
        btn(L+PAD+FW+4,y,50,"Добавить",()->{
            if(!newKeyword.isBlank()){
                if(cfg.alertKeywords==null)cfg.alertKeywords=new ArrayList<>();
                cfg.alertKeywords=new ArrayList<>(cfg.alertKeywords);
                cfg.alertKeywords.add(newKeyword.strip());
                MoggConfig.save(cfg);clearAndInit();}});y+=ROW+2;
        btn(L+PAD,y,80,"Очистить всё",()->{cfg.alertKeywords=new ArrayList<>();MoggConfig.save(cfg);clearAndInit();});y+=ROW;
        tog(y,"Переслать ЛС в Telegram",()->cfg.forwardPm,v->cfg.forwardPm=v);y+=ROW;
        fld(y,"Признак ЛС (фраза в сообщении)",cfg.pmPattern,20,false,s->cfg.pmPattern=s);y+=ROW;
        note("Алерт: Telegram пишет когда слово появляется в чате.",y+2);
        note("ЛС: ищет pmPattern в строке чата и пересылает.",y+13);}

    private void bHud(int y){
        tog(y,"Показывать мини-оверлей (HUD)",()->cfg.showHud,v->cfg.showHud=v);y+=ROW;
        tog(y,"Якорь по правой стороне",()->cfg.hudRight,v->cfg.hudRight=v);y+=ROW;
        tog(y,"Якорь по нижней стороне",()->cfg.hudBottom,v->cfg.hudBottom=v);y+=ROW;
        num(y,"Отступ X (пиксели)",cfg.hudX,0,v->cfg.hudX=v);y+=ROW;
        num(y,"Отступ Y (пиксели)",cfg.hudY,0,v->cfg.hudY=v);y+=ROW;
        // Live preview inside tab
        y+=8;
        lbls.add(new int[]{L+PAD,y,0xFFAAAAFF,lblTxt.size()});lblTxt.add("Предпросмотр:");y+=12;
        int px=L+PAD, py=y, l1w=textRenderer.getWidth("MoggSync ●"), l2w=textRenderer.getWidth("4:23 до приглашения"), l3w=textRenderer.getWidth("0 rit • 0 рил");
        int bw=Math.max(l1w,Math.max(l2w,l3w))+10, bh=34;
        lbls.add(new int[]{px+3,py+2, mod.isEnabled()?0xFFAAFFCC:0xFFFF9999, lblTxt.size()}); lblTxt.add("MoggSync "+(mod.isEnabled()?"●":"○"));
        lbls.add(new int[]{px+3,py+12,0xFF9999FF,lblTxt.size()}); lblTxt.add(mod.timerText().length()>24?mod.timerText().substring(0,24):mod.timerText());
        lbls.add(new int[]{px+3,py+22,0xFFFFCC66,lblTxt.size()}); lblTxt.add(mod.getRitualsDone()+" rit • "+fmt(mod.getTotalRilliki()));
        note("Оверлей скрывается при открытых экранах/меню.",y+38);
        note("Якоря: Левый верхний = оба ВЫКЛ, Правый верхний = Right ВКЛ и т.д.",y+49);}

    private String nm(){return cfg.tgChatMode==null?"OFF":cfg.tgChatMode.toUpperCase(Locale.ROOT);}
    private Text cmt(){return Text.literal(switch(nm()){case"ALL"->"ВЕСЬ ЧАТ";case"MENTIONS"->"ПРО МЕНЯ";default->"ВЫКЛ";});}
    private int cmc(){return switch(nm()){case"ALL"->C_GRN;case"MENTIONS"->C_ACC;default->C_RED;};}

    private void bTelegram(int y){
        tog(y,"Отчёты о ритуалах",()->cfg.telegramEnabled,v->cfg.telegramEnabled=v);y+=ROW;
        fld(y,"Токен бота (@BotFather)",cfg.TELEGRAM_BOT_TOKEN,100,false,s->cfg.TELEGRAM_BOT_TOKEN=s.strip());y+=ROW;
        fld(y,"Chat ID",cfg.CHAT_ID,24,false,s->{cfg.CHAT_ID=s.strip();shownCid=cfg.CHAT_ID;});y+=ROW;
        num(y,"Авто-скрин каждые N мин (0=выкл)",cfg.autoScreenshotMinutes,0,v->cfg.autoScreenshotMinutes=v);y+=ROW;
        lbl("Чат игры в Telegram",y);
        final FB[] cm={null};
        cm[0]=new FB(fx(),y,FW,18,cmt(),b->{cfg.tgChatMode=switch(nm()){case"OFF"->"MENTIONS";case"MENTIONS"->"ALL";default->"OFF";};cm[0].setMessage(cmt());cm[0].col(cmc());},cmc());
        addDrawableChild(cm[0]);y+=ROW;
        tog(y,"Управление игрой из Telegram",()->cfg.tgControl,v->cfg.tgControl=v);y+=ROW;
        int bw=(W-PAD*2-8)/3;
        btn(L+PAD,y+3,bw,"Найти Chat ID",mod::linkTelegram);
        btn(L+PAD+bw+4,y+3,bw,"Тест",mod::telegramTest);
        btn(L+PAD+bw*2+8,y+3,bw,"Скриншот",()->{close();mod.requestShot();});y+=ROW+6;
        note("Управление: текст→чат, /help→команды, !cmd→/команда, /report→сессия.",y+2);
        note("Нужен отдельный бот на каждый ПК (иначе конфликт polling).",y+13);}

    // ===== Render =====
    @Override public void tick(){if(tab==5&&!cfg.CHAT_ID.equals(shownCid))clearAndInit();}
    @Override public boolean shouldPause(){return false;}
    @Override public void close(){mod.onConfigChanged();MoggConfig.save(cfg);super.close();}
    @Override public void renderBackground(DrawContext ctx,int mx,int my,float d){}

    @Override
    public void render(DrawContext ctx,int mx,int my,float delta){
        long now=System.currentTimeMillis();
        float prog=Math.min(1f,(now-openTime)/280f),ease=1f-(1f-prog)*(1f-prog);
        float pulse=(float)(Math.sin((now%4000)*Math.PI*2/4000)*0.5+0.5);
        int ap=lerp(C_ACC,C_GLW,pulse);

        ctx.fill(0,0,width,height,al(0,  (int)(ease*150)));
        ctx.fill(L+5,T+5,L+W+5,T+H+5,   al(0,  (int)(ease*80)));
        ctx.fill(L-3,T-3,L+W+3,T+H+3,   al(ap&0xFFFFFF,(int)(ease*(25+pulse*40))));
        ctx.fill(L-1,T-1,L+W+1,T+H+1,   al(C_ACC&0xFFFFFF,(int)(ease*190)));
        ctx.fill(L,T,L+W,T+H,C_PNL);
        ctx.fill(L,T,L+W,T+44,C_HDR);
        ctx.fill(L,T,L+W,T+10,al(0xFFFFFF,5));
        ctx.fill(L+PAD,T+10,L+PAD+3,T+34,ap);
        ctx.fill(L,T+44,L+W,T+46,ap);
        ctx.fill(L,T+46,L+W,T+48,al(C_ACC&0xFFFFFF,(int)(pulse*55)));

        ctx.drawText(textRenderer,"MoggSync",L+PAD+9,T+11,C_WHT,true);
        ctx.drawText(textRenderer,"парный ритуал  •  автоматизация",L+PAD+9,T+23,C_DIM,false);

        // Stats card
        int sy=T+70;
        ctx.fill(L+PAD,sy,L+W-PAD,sy+22,C_CARD);
        ctx.fill(L+PAD,sy,L+PAD+2,sy+22,ap);
        ctx.fill(L+PAD+2,sy+21,L+W-PAD,sy+22,C_BOR);
        int cw=(W-PAD*2)/3;
        ctx.drawCenteredTextWithShadow(textRenderer,mod.getRitualsDone()+" ритуалов",L+PAD+cw/2,sy+7,C_ORG);
        ctx.drawCenteredTextWithShadow(textRenderer,fmt(mod.getTotalRilliki())+" риллик",L+PAD+cw+cw/2,sy+7,C_GRN);
        String tm=mod.timerText();if(tm.length()>20)tm=tm.substring(0,20);
        ctx.drawCenteredTextWithShadow(textRenderer,tm,L+PAD+cw*2+cw/2,sy+7,0xFF8888FF);

        ctx.fill(L+PAD,T+93,L+W-PAD,T+94,C_BOR);
        ctx.fill(L+PAD,T+96,L+W-PAD,T+H-38,al(0,12));

        // HUD preview box (tab 4)
        if(tab==4&&cfg.showHud){
            int py=T+98+5*ROW+20, px=L+PAD;
            int l1w=textRenderer.getWidth("MoggSync ●"),l2w=textRenderer.getWidth("4:23 до приглашения"),l3w=textRenderer.getWidth("0 rit • 0 рил");
            int bw=Math.max(l1w,Math.max(l2w,l3w))+10;
            ctx.fill(px-1,py-1,px+bw+1,py+35,al(0,180));
            ctx.fill(px-1,py-1,px+1,py+35,mod.isEnabled()?C_GRN:C_RED);
        }

        for(int i=0;i<lbls.size();i++){int[]l=lbls.get(i);ctx.drawText(textRenderer,lblTxt.get(i),l[0],l[1],l[2],false);}

        ctx.fill(L,T+H-38,L+W,T+H-37,C_BOR);
        String inf=mod.lastInfo();
        if(inf!=null&&!inf.isEmpty()){if(inf.length()>52)inf=inf.substring(0,52)+"...";ctx.drawText(textRenderer,inf,L+PAD,T+H-28,C_DIM,false);}

        super.render(ctx,mx,my,delta);
    }
}
