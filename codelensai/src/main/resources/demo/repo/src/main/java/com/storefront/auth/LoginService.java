package com.storefront.auth;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class LoginService {

    private final Connection connection;

    public LoginService(Connection connection) {
        this.connection = connection;
    }

    public boolean login(String email, String password) throws SQLException {
        return false;
    }
}
