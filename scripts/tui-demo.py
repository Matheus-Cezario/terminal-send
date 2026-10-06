"""Drives two real terminal-send TUI clients through a pseudo-terminal and prints their screens.

Requires the server on :8080 (docker compose up -d && ./gradlew :server:bootRun), the client jar
(./gradlew :client:shadowJar) and `pip install pyte pexpect`. Usage: python3 scripts/tui-demo.py
"""
import json
import os
import shutil
import sys
import tempfile
import time
import urllib.request
import uuid

import pexpect
import pyte

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAR = os.path.join(REPO, "client", "build", "libs", "terminal-send.jar")
SCRATCH = tempfile.mkdtemp(prefix="terminal-send-demo-")
COLS, ROWS = 110, 30
TAB, ENTER = "\t", "\r"


class Term:
    def __init__(self, name):
        self.name = name
        home = os.path.join(SCRATCH, "demo-home", name)
        shutil.rmtree(home, ignore_errors=True)
        self.screen = pyte.Screen(COLS, ROWS)
        self.stream = pyte.ByteStream(self.screen)
        env = dict(os.environ, TERM="xterm-256color", LANG="en_US.UTF-8", LC_ALL="en_US.UTF-8")
        self.child = pexpect.spawn("java", ["-jar", JAR, "--home", home, "--server", "http://localhost:8080"],
                                   dimensions=(ROWS, COLS), env=env)

    def pump(self, seconds=0.5):
        end = time.time() + seconds
        while time.time() < end:
            try:
                self.stream.feed(self.child.read_nonblocking(65536, timeout=0.1))
            except pexpect.TIMEOUT:
                pass
            except pexpect.EOF:
                break

    def text(self):
        return "\n".join(line.rstrip() for line in self.screen.display)

    def wait_for(self, needle, timeout=20):
        end = time.time() + timeout
        while time.time() < end:
            self.pump(0.3)
            if needle in self.text():
                return
        self.show(f"TIMEOUT waiting for {needle!r}")
        raise SystemExit(1)

    def type(self, keys, settle=0.4):
        for ch in keys:
            self.child.send(ch)
            time.sleep(0.01)
        self.pump(settle)

    def show(self, title):
        print(f"\n===== {self.name}: {title} " + "=" * max(0, 80 - len(title)))
        print(self.text())


def latest_code(email):
    with urllib.request.urlopen(f"http://localhost:8025/api/v1/search?query=to:{email}&limit=1") as r:
        messages = json.load(r)["messages"]
    return messages[0]["Subject"][-6:]


def sign_up(term, email, password):
    term.wait_for("Senha")
    term.type(email + TAB + password + TAB + TAB + ENTER, settle=2)   # Email, Senha, [Entrar] -> [Criar conta]
    term.wait_for("código de 6 dígitos")
    time.sleep(1)
    term.type(latest_code(email) + TAB + TAB + TAB + ENTER, settle=3)  # Código -> Entrar, Criar conta, [Verificar]
    term.wait_for("Bem-vindo")


def main():
    suffix = uuid.uuid4().hex[:6]
    ana_email, bruno_email = f"ana-{suffix}@example.com", f"bruno-{suffix}@example.com"
    password = "correct horse battery"

    ana, bruno = Term("ana"), Term("bruno")
    ana.wait_for("Senha")
    ana.show("tela de login")

    sign_up(ana, ana_email, password)
    sign_up(bruno, bruno_email, password)
    ana.wait_for("online")
    ana.show("logada, sem contatos")

    ana.type(f"/add {bruno_email}" + ENTER, settle=1.5)
    bruno.wait_for("(convite)")
    bruno.type("/invites" + ENTER, settle=1)
    bruno.show("convite recebido ao vivo")

    bruno.type("/accept 1" + ENTER, settle=2)
    ana.wait_for(f"● bruno-{suffix}")
    ana.type(TAB + ENTER, settle=1)   # focus the contact list and open the conversation
    ana.type("oi bruno! tudo certo por aí? 🔐" + ENTER, settle=2)
    bruno.wait_for("tudo certo")
    bruno.type("tudo ótimo, ana! chegou cifrada de ponta a ponta" + ENTER, settle=2)
    ana.wait_for("chegou cifrada")
    ana.pump(1.5)
    ana.show("conversa (✓✓ = entregue)")
    bruno.show("conversa do lado do bruno")

    bruno.type("/verify" + ENTER, settle=1)
    bruno.show("/verify mostra os fingerprints")

    # Bruno quits; Ana writes while he's offline; he comes back and receives it.
    bruno.type("/quit" + ENTER, settle=2)
    bruno.child.expect(pexpect.EOF, timeout=10)
    ana.type("mandei enquanto você estava offline" + ENTER, settle=2)
    ana.show("bruno offline: só ✓ (servidor guardou)")

    # same home dir as before -> same identity key and same local history
    bruno2 = reopen("bruno")
    bruno2.wait_for("Senha")
    bruno2.type(bruno_email + TAB + password + TAB + ENTER, settle=3)  # [Entrar]
    bruno2.wait_for("Bem-vindo")
    bruno2.pump(2)
    bruno2.type(TAB + ENTER, settle=1.5)
    bruno2.show("bruno voltou: histórico local + mensagem offline")
    ana.pump(2)
    ana.show("ana vê ✓✓ depois que o bruno voltou")

    for t in (ana, bruno2):
        t.type("/quit" + ENTER, settle=1)
        t.child.expect(pexpect.EOF, timeout=10)
    print("\nDEMO OK")


def reopen(name):
    term = Term.__new__(Term)
    term.name = name
    term.screen = pyte.Screen(COLS, ROWS)
    term.stream = pyte.ByteStream(term.screen)
    env = dict(os.environ, TERM="xterm-256color", LANG="en_US.UTF-8", LC_ALL="en_US.UTF-8")
    home = os.path.join(SCRATCH, "demo-home", name)
    term.child = pexpect.spawn("java", ["-jar", JAR, "--home", home, "--server", "http://localhost:8080"],
                               dimensions=(ROWS, COLS), env=env)
    return term


if __name__ == "__main__":
    sys.exit(main())
