package com.example;

import org.epsec.core.MethodBan;

class HelloWorld {

  @MethodBan(times = 1, seconds = 10, banSeconds = 20)
  public String hello() {
    return "Hello World!";
  }
}