package com.mustafadakhel.kodex.util

public class NoSuchRoleException(name: String) : Throwable("Role $name does not exist")