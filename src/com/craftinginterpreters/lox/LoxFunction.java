package com.craftinginterpreters.lox;

import java.util.List;

public class LoxFunction implements LoxCallable {
  private String name;
  private List<Token> params;
  private List<Stmt> body;
  private Environment closure;

  LoxFunction(String name, List<Token> params, List<Stmt> body, Environment closure) {
    this.name = name;
    this.params = params;
    this.body = body;
    this.closure = closure;
  }

  @Override
  public int arity() {
    return params.size();
  }

  @Override
  public Object call(Interpreter interpreter, List<Object> arguments) {
    Environment environment = new Environment(closure);
    for (int i = 0; i < arity(); i++) {
      environment.define(params.get(i).lexeme, arguments.get(i));
    }

    try {
      interpreter.executeBlock(body, environment);
    } catch (Return ret) {
      return ret.value;
    }

    return null;
  }

  @Override
  public String toString() {
    return "<fn " + name + ">";
  }
}
