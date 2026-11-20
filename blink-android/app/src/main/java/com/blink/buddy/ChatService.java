package com.blink.buddy;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

/**
 * Hosts the chat brain in its own process (":chat", see the manifest). The app talks to it with simple messages;
 * a native crash in the chat engine ends only this process, never Sweety herself.
 */
public class ChatService extends Service {
    static final int WARM = 1, REPLY = 2;

    private HandlerThread thread;
    private Messenger messenger;
    private ChatEngine engine;

    @Override
    public void onCreate() {
        super.onCreate();
        engine = new ChatEngine(ChatBrain.modelFile(this), getCacheDir());
        thread = new HandlerThread("sweety-chat-engine");
        thread.start();
        messenger = new Messenger(new Handler(thread.getLooper(), this::handle));
    }

    private boolean handle(Message m) {
        Bundle out = new Bundle();
        if (m.what == WARM) engine.ensure();
        else if (m.what == REPLY) out.putString("text", engine.reply(m.getData().getString("text")));
        out.putBoolean("ready", engine.ready());
        out.putString("failure", engine.failure());
        if (m.replyTo != null) {
            Message back = Message.obtain(null, m.what);
            back.arg1 = m.arg1;
            back.setData(out);
            try { m.replyTo.send(back); } catch (RemoteException ignored) { }
        }
        return true;
    }

    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }

    @Override
    public void onDestroy() {
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }
}
