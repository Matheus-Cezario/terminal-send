package dev.terminalsend.server.user;

import dev.terminalsend.protocol.rest.AuthDtos.UserView;

public final class UserViews {

    private UserViews() {
    }

    public static UserView of(User user) {
        return new UserView(user.getId(), user.getEmail(), user.getHandle(), user.isEmailVerified());
    }
}
