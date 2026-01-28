package com.test;

public class HelloWorld {
  public static void main(String[] args) {
    System.out.println("Hello from Jib with containerizing mode!");
    for (String arg : args) {
      System.out.println("Arg: " + arg);
    }
  }
}
