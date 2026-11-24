#!/usr/bin/env python3
"""A stand-in for Google's Gemini API, so CI can test Sweety's smart brain without a real key.
It answers like Gemini would (text with a [gesture|mood] tag, or function calls) and records every request
in requests.jsonl so the test can check what the app sent.   mock_gemini.py <port> <log_dir>"""
import json, os, sys
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT, LOG = int(sys.argv[1]), sys.argv[2]
os.makedirs(LOG, exist_ok=True)

MODELS = {"models": [
    {"name": "models/gemini-2.5-flash", "supportedGenerationMethods": ["generateContent"]},
    # the newest model, but not on this (free) key: like a real key that can't use Google's latest model yet
    {"name": "models/gemini-4.0-flash", "supportedGenerationMethods": ["generateContent"]},
    # overloaded, like Google's Flash models often are ("high demand", 503)
    {"name": "models/gemini-3.9-flash", "supportedGenerationMethods": ["generateContent"]},
    {"name": "models/gemini-3.8-flash", "supportedGenerationMethods": ["generateContent", "countTokens"]},
    {"name": "models/gemini-3.8-flash-lite", "supportedGenerationMethods": ["generateContent"]},
    {"name": "models/gemini-3.8-flash-image", "supportedGenerationMethods": ["generateContent"]},
    {"name": "models/gemini-3.5-pro", "supportedGenerationMethods": ["generateContent"]},
    {"name": "models/text-embedding-004", "supportedGenerationMethods": ["embedContent"]},
]}


def text(t):
    return {"candidates": [{"content": {"role": "model", "parts": [{"text": t}]}}]}


def call(name, args, t=None):
    parts = ([{"text": t}] if t else []) + [{"functionCall": {"name": name, "args": args}}]
    return {"candidates": [{"content": {"role": "model", "parts": parts}}]}


def answer(body):
    user = body["contents"][-1]["parts"][0]["text"].lower()
    system = body["systemInstruction"]["parts"][0]["text"]
    searched = "google_search" in json.dumps(body.get("tools", []))
    if "weather" in user:
        return text("[nod|happy] It's sunny and 28 degrees in Bengaluru today." if searched else "[shrug|curious] I can't look that up right now!")
    if "news" in user:
        return text("[shrug|curious] I couldn't search just now, but I can tell you what I know!" if not searched else "[nod|happy] Searched news.")
    if "sky" in user:
        return text("[nod|happy] Sunlight scatters off the air, and blue light scatters the most, so the sky looks blue. Pretty, right?")
    if "remind" in user and "water" in user:
        return call("set_timer", {"minutes": 30, "label": "drink water"})
    if "my name is" in user:
        return call("remember", {"fact": "The user's name is Alex"}, "[wave|love] Nice to meet you, Alex! I'll remember that.")
    if "what's my name" in user or "what is my name" in user:
        known = "Alex" in system
        return text("[jump|happy] You're Alex, of course!" if known else "[shrug|curious] Hmm, you haven't told me yet!")
    if "what did i just ask" in user:
        earlier = [c["parts"][0].get("text", "") for c in body["contents"][:-1] if c["role"] == "user"]
        return text(f"[nod|curious] You asked: {earlier[-1] if earlier else 'nothing yet'}")
    if "butterfly" in user:
        return call("sweety_fun", {"action": "catch", "critter": "butterfly"})
    return text("[nod|happy] Okay!")


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def send_stream(self, obj):
        """Like streamGenerateContent?alt=sse: the answer's text in a few pieces, then any function calls."""
        parts = obj["candidates"][0]["content"]["parts"]
        text = "".join(p.get("text", "") for p in parts)
        chunks = [text[i:i + 17] for i in range(0, len(text), 17)] or [""]
        events = [{"candidates": [{"content": {"role": "model", "parts": [{"text": c}]}}]} for c in chunks]
        calls = [p for p in parts if "functionCall" in p]
        if calls:
            events.append({"candidates": [{"content": {"role": "model", "parts": calls}}]})
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        for e in events:
            self.wfile.write(("data: " + json.dumps(e) + "\r\n\r\n").encode())
            self.wfile.flush()

    def check_key(self):
        if self.headers.get("x-goog-api-key") != "test-key":
            self.send(400, {"error": {"code": 400, "message": "API key not valid. Please pass a valid API key."}})
            return False
        return True

    def do_GET(self):
        if not self.check_key():
            return
        self.send(200, MODELS) if self.path.startswith("/v1beta/models") else self.send(404, {"error": {"message": "not found"}})

    def do_POST(self):
        if not self.check_key():
            return
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
        with open(f"{LOG}/requests.jsonl", "a") as f:
            f.write(json.dumps({"path": self.path, "body": body}) + "\n")
        if "gemini-4.0-flash:" in self.path:
            self.send(429, {"error": {"code": 429, "status": "RESOURCE_EXHAUSTED",
                                      "message": "Quota exceeded for metric: generate_content_free_tier_requests, limit: 0"}})
            return
        if "gemini-3.9-flash:" in self.path:
            self.send(503, {"error": {"code": 503, "status": "UNAVAILABLE",
                                      "message": "This model is currently experiencing high demand. Spikes in demand are usually temporary. Please try again later."}})
            return
        tools = json.dumps(body.get("tools", []))
        if "google_search" in tools:  # like a free key without Search for "news" questions; with it for the weather
            if "news" in body["contents"][-1]["parts"][0]["text"].lower():
                self.send(429, {"error": {"code": 429, "status": "RESOURCE_EXHAUSTED", "message": "You exceeded your current quota, please check your plan and billing details."}})
                return
        thinking = body.get("generationConfig", {}).get("thinkingConfig", {})
        if "thinkingBudget" in thinking and "gemini-3" in self.path:  # like the real API: Gemini 3 takes a thinkingLevel
            self.send(400, {"error": {"code": 400, "message": "thinking_budget is not supported by this model, use thinking_level"}})
            return
        if "gemini-3.8-flash:" not in self.path:
            self.send(404, {"error": {"message": f"model {self.path} not found"}})
            return
        if ":streamGenerateContent" in self.path:
            self.send_stream(answer(body))
        else:
            self.send(200, answer(body))


HTTPServer(("127.0.0.1", PORT), H).serve_forever()
