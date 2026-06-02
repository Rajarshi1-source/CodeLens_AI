package com.codelensai.model.dto;

import com.codelensai.model.entity.User;

/** Safe user projection — deliberately omits {@code access_token}. */
public record UserDto(long id, String username, String avatarUrl) {
    public static UserDto from(User u) {
        return new UserDto(u.getId(), u.getUsername(), u.getAvatarUrl());
    }
}
