package dev.terminalsend.client.app;

import dev.terminalsend.client.store.StoredMessage;

/** What the UI needs to hear about. Called from background threads; implementations must hop to their own. */
public interface ChatEvents {

    void contactsChanged();

    /** A message was added or its delivery state changed. */
    void messageChanged(StoredMessage message);

    void notice(String text);

    void connectionChanged(boolean online);

    /** Another login took over this account; the session is no longer live. */
    void sessionReplaced();

    ChatEvents NONE = new ChatEvents() {
        @Override
        public void contactsChanged() {
        }

        @Override
        public void messageChanged(StoredMessage message) {
        }

        @Override
        public void notice(String text) {
        }

        @Override
        public void connectionChanged(boolean online) {
        }

        @Override
        public void sessionReplaced() {
        }
    };
}
