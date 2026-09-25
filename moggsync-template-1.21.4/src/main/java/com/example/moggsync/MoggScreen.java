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

    private static final int W = 452, H = 340, PAD = 16, ROW = 21, FW = 192;
    private static final String[] TABS = {"Основное","Ритуал","Лобби","Сервер","Telegram","Защита"};
    private static final int
        C_PANEL=0xFF13151F, C_HEAD=0xFF181A28, C_CARD=0xFF1C1F2E, C_BORDER=0xFF282B3F,
        C_ACCENT=0xFF7C5CFF, C_GLOW=0xFF9D7FFF,
        C_GREEN=0xFF2FBF71, C_RED=0xFFD64545, C_ORANGE=0xFFFF9038,
        C_TEXT=0xFFCDD5F0, C_MUTED=0xFF6870A0, C_BRIGHT=0xFFEEF2FF;

    private final MoggSyncClient mod;
    private final MoggConfig cfg;
    private int tab=0, L, T;
    private long openTime=-1;
    private String shownChatId="";
    private final List<long[]> lbls=new ArrayList<>();  // {x,y,color,strIdx}
    private final List<String> lblText=new ArrayList<>();

    public MoggScreen(){super(Text.literal("MoggSync"));mod=MoggSyncClient.get();cfg=mod.cfg();}

    // ===== FlatButton =====
    private static final class FB extends ButtonWidget {
        private int col;
        FB(int x,int y,int w,int h,Text t,PressAction a,int col){super(x,y,w,h,t,a,DEFAULT_NARRATION_SUPPLIER);this.col=col;}
        void setCol(int c){col=c;}
        @Override public void renderWidget(DrawContext ctx,int mx,int my,float d){
            int x=getX(),y=getY(),w=getWidth(),h=getHeight();
            int bg=isHovered()?br(col,28):col;
            ctx.fill(x,y,x+w,y+h,bg);
            ctx.fill(x,y+h-1,x+w,y+h,0x30000000);
            ctx.fill(x,y,x+w,y+1,0x20FFFFFF);
            ctx.drawCenteredTextWithShadow(MinecraftClient.getInstance().textRenderer,getMessage(),x+w/2,y+(h-8)/2,C_BRIGHT);
        }
    }
    private static int br(int c,int a){return 0xFF000000|(Math.min(255,((c>>16)&255)+a)<<16)|(Math.min(255,((c>>8)&255)+a)<<8)|Math.min(255,(c&255)+a);}
    private static int lrp(int a,int b,float t){return 0xFF000000|((((a>>16)&255)+(int)(t*((b>>16&255)-(a>>16&255))))<<16)|((((a>>8)&255)+(int)(t*((b>>8&255)-(a>>8&255))))<<8)|(((a&255)+(int)(t*((b&255)-(a&255)))));}
    private static int alp(int c,int a){return(a<<24)|(c&0xFFFFFF);}

    // ===== Init =====
    @Override protected void init(){
        if(openTime<0)openTime=System.currentTimeMillis();
        L=(width-W)/2; T=(height-H)/2;
        lbls.clear(); lblText.clear();
        shownChatId=cfg.CHAT_ID;

        // Master ON/OFF
        final FB[] m={null}; boolean on=mod.isEnabled();
        m[0]=new FB(L+W-PAD-118,T+8,118,26,Text.literal(on?"● МОД ВКЛЮЧЁН":"○ МОД ВЫКЛЮЧЕН"),b->{
            boolean v=!mod.isEnabled(); mod.setEnabled(v);
            m[0].setMessage(Text.literal(v?"● МОД ВКЛЮЧЁН":"○ МОД ВЫКЛЮЧЕН"));
            m[0].setCol(v?C_GREEN:C_RED);
        },on?C_GREEN:C_RED);
        addDrawableChild(m[0]);

        // Tabs (6)
        int tw=(W-PAD*2-(TABS.length-1)*3)/TABS.length;
        for(int i=0;i<TABS.length;i++){final int ix=i;
            addDrawableChild(new FB(L+PAD+i*(tw+3),T+48,tw,19,Text.literal(TABS[i]),
                b->{tab=ix;clearAndInit();},i==tab?C_ACCENT:C_BORDER));}

        // Content
        int cy=T+98;
        switch(tab){case 0->bMain(cy);case 1->bRitual(cy);case 2->bLobby(cy);case 3->bServer(cy);case 4->bTelegram(cy);case 5->bProtect(cy);}

        addDrawableChild(new FB(L+W-PAD-120,T+H-32,120,22,Text.literal("Готово"),b->close(),C_GREEN));
    }

    private int fx(){return L+W-PAD-FW;}
    private void lbl(String t,int y){lblText.add(t);lbls.add(new long[]{L+PAD,y+5,C_TEXT});}
    private void note(String t,int y){lblText.add(t);lbls.add(new long[]{L+PAD,y,C_MUTED});}

    private void field(int y,String lb,String v,int max,boolean num,Consumer<String> cb){
        lbl(lb,y);
        var f=new TextFieldWidget(textRenderer,fx(),y,FW,18,Text.literal(lb));
        f.setMaxLength(max);if(num)f.setTextPredicate(s->s.matches("\\d*"));
        f.setText(v==null?"":v);f.setChangedListener(cb);addDrawableChild(f);}

    private void num(int y,String lb,int v,int mn,IntConsumer s){
        field(y,lb,String.valueOf(v),6,true,t->{if(!t.isEmpty())try{s.accept(Math.max(mn,Integer.parseInt(t)));}catch(NumberFormatException e){}});}

    private void toggle(int y,String lb,Supplier<Boolean> g,Consumer<Boolean> s){
        lbl(lb,y);final FB[] r={null};boolean on=g.get();
        r[0]=new FB(fx(),y,FW,18,Text.literal(on?"ВКЛ":"ВЫКЛ"),b->{
            boolean v=!g.get();s.accept(v);
            r[0].setMessage(Text.literal(v?"ВКЛ":"ВЫКЛ"));r[0].setCol(v?C_GREEN:C_RED);
        },on?C_GREEN:C_RED);addDrawableChild(r[0]);}

    private FB btn(int x,int y,int w,String t,Runnable r){
        return addDrawableChild(new FB(x,y,w,19,Text.literal(t),b->r.run(),C_BORDER));}

    // ===== Tab content =====
    private void bMain(int y){
        toggle(y,"Авто-включение при запуске",()->cfg.enabledOnStart,v->cfg.enabledOnStart=v);y+=ROW;
        field(y,"Только на аккаунтах (Ник1,Ник2)",cfg.activeAccounts,80,false,s->cfg.activeAccounts=s.strip());y+=ROW;
        field(y,"Ник напарника",cfg.partnerName,16,false,s->cfg.partnerName=s.strip());y+=ROW;
        field(y,"Фраза-приглашение в чат",cfg.readyPhrase,64,false,s->cfg.readyPhrase=s);y+=ROW;
        field(y,"Пароль от аккаунта",cfg.password,64,false,s->cfg.password=s);y+=ROW;
        toggle(y,"Авто-ввод пароля (LOGIN экран)",()->cfg.autoLogin,v->cfg.autoLogin=v);y+=ROW;
        toggle(y,"Авто-переподключение",()->cfg.autoReconnect,v->cfg.autoReconnect=v);y+=ROW;
        note("Пусто = любой аккаунт. Пароль — только локально, в чат не уходит.",y+5);
        note("Мод молчит на аккаунтах НЕ из списка.",y+16);}

    private void bRitual(int y){
        num(y,"Кд от (сек)",cfg.timerMinSeconds,1,v->cfg.timerMinSeconds=v);y+=ROW;
        num(y,"Кд до (сек)",cfg.timerMaxSeconds,1,v->cfg.timerMaxSeconds=v);y+=ROW;
        num(y,"Повтор приглашения (сек)",cfg.retrySeconds,5,v->cfg.retrySeconds=v);y+=ROW;
        num(y,"Ответ напарнику: задержка от (мс)",cfg.replyMinMs,0,v->cfg.replyMinMs=v);y+=ROW;
        num(y,"Ответ напарнику: задержка до (мс)",cfg.replyMaxMs,0,v->cfg.replyMaxMs=v);y+=ROW;
        num(y,"Шифт: переключать каждые (мс)",cfg.danceToggleMs,50,v->cfg.danceToggleMs=v);y+=ROW;
        num(y,"Таймаут танца (сек)",cfg.danceTimeoutSeconds,5,v->cfg.danceTimeoutSeconds=v);y+=ROW;
        note("Кд по умолчанию 300-480 с (5-8 мин). Шифт 250 мс = 4 раза в сек.",y+5);}

    private void bLobby(int y){
        toggle(y,"Авто-вход через лобби",()->cfg.autoLobby,v->cfg.autoLobby=v);y+=ROW;
        field(y,"Команда лобби",cfg.lobbyCommand,40,false,s->cfg.lobbyCommand=s.strip());y+=ROW;
        num(y,"Слот хотбара 1-9",cfg.lobbyHotbarSlot+1,1,v->cfg.lobbyHotbarSlot=Math.min(9,v)-1);y+=ROW;
        MoggConfig.MenuStep st=mod.lastStep();
        field(y,"Имя предмета в меню",st.item,40,false,s->mod.lastStep().item=s.strip());y+=ROW;
        num(y,"Слот в меню (запасной)",Math.max(0,st.slot),0,v->mod.lastStep().slot=v);y+=ROW;
        num(y,"Пауза перед кликом (мс)",cfg.menuClickDelayMs,0,v->cfg.menuClickDelayMs=v);y+=ROW;
        field(y,"Команда фарма после входа",cfg.farmCommand,40,false,s->cfg.farmCommand=s.strip());y+=ROW;
        note("Имя предмета важнее слота. Лобби не работает в сингле.",y+5);
        btn(L+PAD,T+H-32,140,"▶ Тест входа в лобби",()->{close();mod.startLobbyTest();});}

    private void bServer(int y){
        field(y,"Заголовок меню содержит",mod.lastStep().title,40,false,s->mod.lastStep().title=s.strip());y+=ROW;
        field(y,"Адрес сервера содержит",cfg.serverAddressContains,40,false,s->cfg.serverAddressContains=s.strip());y+=ROW;
        num(y,"Ждать после входа (мс)",cfg.lobbyJoinDelayMs,0,v->cfg.lobbyJoinDelayMs=v);y+=ROW;
        num(y,"Максимум попыток входа",cfg.maxLobbyAttempts,1,v->cfg.maxLobbyAttempts=v);y+=ROW;
        num(y,"Таймаут перехода (мс)",cfg.transferTimeoutMs,1000,v->cfg.transferTimeoutMs=v);y+=ROW;
        num(y,"Пауза после перехода (мс)",cfg.postTransferDelayMs,0,v->cfg.postTransferDelayMs=v);y+=ROW;
        num(y,"Повтор 'недоступен' (мс)",cfg.unavailableRetryMs,1000,v->cfg.unavailableRetryMs=v);y+=ROW;
        num(y,"Макс. повторов 'недоступен'",cfg.unavailableMaxRetries,1,v->cfg.unavailableMaxRetries=v);y+=ROW;
        note("Адрес пуст = любой мультиплеер. В сингле лобби отключено.",y+5);}

    private String nm(){return cfg.tgChatMode==null?"OFF":cfg.tgChatMode.toUpperCase(Locale.ROOT);}
    private Text cmText(){return Text.literal(switch(nm()){case"ALL"->"ВЕСЬ ЧАТ";case"MENTIONS"->"ПРО МЕНЯ";default->"ВЫКЛ";});}
    private int cmCol(){return switch(nm()){case"ALL"->C_GREEN;case"MENTIONS"->C_ACCENT;default->C_RED;};}

    private void bTelegram(int y){
        toggle(y,"Отчёты о ритуалах",()->cfg.telegramEnabled,v->cfg.telegramEnabled=v);y+=ROW;
        field(y,"Токен бота",cfg.TELEGRAM_BOT_TOKEN,100,false,s->cfg.TELEGRAM_BOT_TOKEN=s.strip());y+=ROW;
        field(y,"Chat ID",cfg.CHAT_ID,24,false,s->{cfg.CHAT_ID=s.strip();shownChatId=cfg.CHAT_ID;});y+=ROW;
        num(y,"Авто-скрин каждые (мин, 0=выкл)",cfg.autoScreenshotMinutes,0,v->cfg.autoScreenshotMinutes=v);y+=ROW;
        lbl("Чат игры в Telegram",y);
        final FB[] cm={null};
        cm[0]=new FB(fx(),y,FW,18,cmText(),b->{
            cfg.tgChatMode=switch(nm()){case"OFF"->"MENTIONS";case"MENTIONS"->"ALL";default->"OFF";};
            cm[0].setMessage(cmText());cm[0].setCol(cmCol());},cmCol());
        addDrawableChild(cm[0]);y+=ROW;
        toggle(y,"Управление игрой из Telegram",()->cfg.tgControl,v->cfg.tgControl=v);y+=ROW;
        int bw=(W-PAD*2-8)/3;
        btn(L+PAD,y+3,bw,"Найти Chat ID",mod::linkTelegram);
        btn(L+PAD+bw+4,y+3,bw,"Тест",mod::telegramTest);
        btn(L+PAD+bw*2+8,y+3,bw,"Скриншот",()->{close();mod.requestShot();});y+=ROW+6;
        note("Текст боту → игровой чат. !cmd → /команда. /help → все команды.",y+2);
        note("Нужен отдельный бот на каждый ПК.",y+13);}

    private void bProtect(int y){
        toggle(y,"Авто-возрождение при смерти",()->cfg.autoRespawn,v->cfg.autoRespawn=v);y+=ROW;
        num(y,"Пауза перед возрождением (мс)",cfg.respawnDelayMs,0,v->cfg.respawnDelayMs=v);y+=ROW;
        field(y,"Телепорт домой после смерти",cfg.respawnHomeCommand,40,false,s->cfg.respawnHomeCommand=s.strip());y+=ROW;
        num(y,"Пауза перед телепортом (мс)",cfg.respawnHomeDelayMs,0,v->cfg.respawnHomeDelayMs=v);y+=ROW;
        toggle(y,"Уведомление о смерти в Telegram",()->cfg.deathNotifyTelegram,v->cfg.deathNotifyTelegram=v);y+=ROW;
        toggle(y,"Анти-АФК (повороты камеры)",()->cfg.antiAfk,v->cfg.antiAfk=v);y+=ROW;
        num(y,"Анти-АФК каждые (минут)",cfg.antiAfkMinutes,1,v->cfg.antiAfkMinutes=v);y+=ROW;
        note("Команда домой: /home, /warp farm и т.п. Пусто = не телепортироваться.",y+5);
        note("После смерти ритуал ставится на паузу и возобновляется после телепорта.",y+16);}

    // ===== Tick / Render =====
    @Override public void tick(){if(tab==4&&!cfg.CHAT_ID.equals(shownChatId))clearAndInit();}
    @Override public boolean shouldPause(){return false;}
    @Override public void renderBackground(DrawContext ctx,int mx,int my,float d){}
    @Override public void close(){mod.onConfigChanged();MoggConfig.save(cfg);super.close();}

    @Override public void render(DrawContext ctx,int mx,int my,float delta){
        long now=System.currentTimeMillis();
        float p=Math.min(1f,(now-openTime)/280f);
        float ease=1f-(1f-p)*(1f-p);
        float pulse=(float)(Math.sin((now%4000)*Math.PI*2/4000)*0.5+0.5);
        int ap=lrp(C_ACCENT,C_GLOW,pulse);

        // Backdrop
        ctx.fill(0,0,width,height,alp(0x000000,(int)(ease*148)));
        // Shadow
        ctx.fill(L+5,T+5,L+W+5,T+H+5,alp(0x000000,(int)(ease*70)));
        // Glow ring (animated)
        ctx.fill(L-3,T-3,L+W+3,T+H+3,alp(ap&0xFFFFFF,(int)(ease*(20+pulse*40))));
        ctx.fill(L-1,T-1,L+W+1,T+H+1,alp(C_ACCENT&0xFFFFFF,(int)(ease*170)));
        // Window
        ctx.fill(L,T,L+W,T+H,C_PANEL);
        // Header
        ctx.fill(L,T,L+W,T+44,C_HEAD);
        ctx.fill(L,T,L+W,T+10,alp(0xFFFFFF,5));
        // Accent left stripe
        ctx.fill(L+PAD,T+10,L+PAD+3,T+34,ap);
        // Accent line
        ctx.fill(L,T+44,L+W,T+46,ap);
        ctx.fill(L,T+46,L+W,T+48,alp(C_ACCENT&0xFFFFFF,(int)(pulse*45)));
        // Header text
        ctx.drawText(textRenderer,"MoggSync",L+PAD+9,T+11,C_BRIGHT,true);
        ctx.drawText(textRenderer,"парный ритуал — автоматизация",L+PAD+9,T+23,C_MUTED,false);

        // Stats card
        int sy=T+70;
        ctx.fill(L+PAD,sy,L+W-PAD,sy+22,C_CARD);
        ctx.fill(L+PAD,sy,L+PAD+2,sy+22,ap);
        ctx.fill(L+PAD+2,sy+21,L+W-PAD,sy+22,C_BORDER);
        int cw=(W-PAD*2)/3;
        ctx.drawCenteredTextWithShadow(textRenderer,mod.getRitualsDone()+" ритуалов",L+PAD+cw/2,sy+7,C_ORANGE);
        ctx.drawCenteredTextWithShadow(textRenderer,fmt(mod.getTotalRilliki())+" риллик",L+PAD+cw+cw/2,sy+7,C_GREEN);
        String tmr=mod.timerText(); if(tmr.length()>22)tmr=tmr.substring(0,22)+"..";
        ctx.drawCenteredTextWithShadow(textRenderer,tmr,L+PAD+cw*2+cw/2,sy+7,0xFF8899FF);

        // Tab separator
        ctx.fill(L+PAD,T+93,L+W-PAD,T+94,C_BORDER);
        // Content bg
        ctx.fill(L+PAD,T+96,L+W-PAD,T+H-38,alp(0x000000,10));

        // Labels
        for(int i=0;i<lblText.size();i++){
            var e=lbls.get(i);
            ctx.drawText(textRenderer,lblText.get(i),(int)e[0],(int)e[1],(int)e[2],false);}

        // Footer
        ctx.fill(L,T+H-38,L+W,T+H-37,C_BORDER);
        String info=mod.lastInfo();
        if(info!=null&&!info.isEmpty()){
            if(info.length()>54)info=info.substring(0,54)+"...";
            ctx.drawText(textRenderer,info,L+PAD,T+H-28,C_MUTED,false);}

        super.render(ctx,mx,my,delta);}

    private static String fmt(long n){
        if(n>=1_000_000)return String.format("%.1fм",n/1_000_000.0);
        if(n>=1000)return String.format("%.1fк",n/1000.0);
        return String.valueOf(n);}
}
