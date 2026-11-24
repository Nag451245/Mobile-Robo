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

import java.io.File;

/**
 * Hosts the chat brain in its own process (":chat", see the manifest). The app talks to it with simple messages;
 * a native crash in the chat engine ends only this process, never Sweety herself. Each request says which brain
 * to use (Gemma 4 or Qwen) and whether to try the GPU; answers can come back piece by piece (PIECE messages).
 */
public class ChatService extends Service {
    static final int WARM = 1, REPLY = 2, PIECE = 3;

    private HandlerThread thread;
    private Messenger messenger;
    private ChatEngine engine;

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("sweety-chat-engine");
        thread.start();
        messenger = new Messenger(new Handler(thread.getLooper(), this::handle));
    }

    /** The engine for the brain this request asks for (a new brain was downloaded or removed: switch). */
    private ChatEngine engineFor(Bundle in) {
        String path = in.getString("model");
        File model = path != null ? new File(path) : ChatBrain.modelFile(this);
        boolean big = in.getBoolean("big"), gpu = in.getBoolean("gpu");
        if (engine == null || !engine.isFor(model, gpu)) {
            if (engine != null) engine.close();
            engine = new ChatEngine(model, getCacheDir(), big, gpu);
        }
        return engine;
    }

    private boolean handle(Message m) {
        Bundle in = m.getData() == null ? new Bundle() : m.getData();
        ChatEngine e = engineFor(in);
        Bundle out = new Bundle();
        if (m.what == WARM) e.ensure();
        else if (m.what == REPLY) {
            final Messenger to = m.replyTo;
            final int seq = m.arg1;
            ChatEngine.Pieces pieces = !in.getBoolean("stream") || to == null ? null : text -> {
                Message p = Message.obtain(null, PIECE);
                p.arg1 = seq;
                Bundle b = new Bundle();
                b.putString("text", text);
                p.setData(b);
                try { to.send(p); } catch (RemoteException ignored) { }
            };
            out.putString("text", e.reply(in.getString("text"), pieces));
        }
        out.putBoolean("ready", e.ready());
        out.putString("failure", e.failure());
        out.putString("backend", e.backend());
        out.putBoolean("gpuFailed", e.gpuFailed());
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
        if (engine != null) engine.close();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }
}
