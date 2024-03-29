package com.craftinginterpreters.lox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.craftinginterpreters.lox.TokenType.*;

public class Parser {
  private static class ParseError extends RuntimeException {}

  private final List<Token> tokens;
  private int current = 0;

  private int loopCounter = 0;
  
  Parser(List<Token> tokens) {
    this.tokens = tokens;
  }

  List<Stmt> parse() {
    List<Stmt> statements = new ArrayList<Stmt>();
    while (!isAtEnd()) {
      statements.add(declaration());
    }

    return statements;
  }

  private Stmt declaration() {
    try {
      if (check(FUN) && checkNext(IDENTIFIER)) {
        match(FUN);
        return function("function");
      }
      if (match(VAR)) return varDeclaration();

      return statement();
    } catch (ParseError error) {
      synchronize();
      return null;
    }
  }

  private Stmt function(String kind) {
    Token name = consume(IDENTIFIER, "Expect " + kind + " name.");
    
    List<Token> params = parameters(kind);
    List<Stmt> body = body(kind);

    return new Stmt.Function(name, params, body);
  }

  private List<Token> parameters(String kind) {
    consume(LEFT_PAREN, "Expect '(' after " + kind + " name.");

    List<Token> params = new ArrayList<Token>();
    if (match(IDENTIFIER)) {
      Token param = previous();
      while (true) {
        if (params.size() >= 255) {
          error(peek(), "Can't have more than 255 parameters.");
        }

        params.add(param);

        if (!match(COMMA)) {
          break;
        }

        param = consume(IDENTIFIER, "Expect parameter after ','.");
      }
    }

    consume(RIGHT_PAREN, "Expect ')' after parameters.");
    return params;
  }

  private List<Stmt> body(String kind) {
    consume(LEFT_BRACE, "Expect '{' before " + kind + " body.");
    return block();
  }

  private Stmt varDeclaration() {
    Token name = consume(IDENTIFIER, "Expect variable name.");

    Expr initializer = null;
    if (match(EQUAL)) {
      initializer = expression();
    }

    consume(SEMICOLON, "Expect ';' after variable declaration.");
    return new Stmt.Var(name, initializer);
  }

  private Stmt statement() {
    if (match(PRINT)) return printStatement();
    if (match(LEFT_BRACE)) return new Stmt.Block(block());
    if (match(IF)) return ifStatement();
    if (match(WHILE)) return whileStatement();
    if (match(FOR)) return forStatement();
    if (match(BREAK)) return breakStatement();
    if (match(RETURN)) return returnStatement();

    return expressionStatement();
  }

  private Stmt printStatement() {
    Expr value = expression();
    consume(SEMICOLON, "Expect ';' after value.");
    return new Stmt.Print(value);
  }

  private List<Stmt> block() {
    List<Stmt> statements = new ArrayList<Stmt>();
    while (!check(RIGHT_BRACE) && !isAtEnd()) {
      statements.add(declaration());
    }

    consume(RIGHT_BRACE, "Expected '}' to close out block statement.");
    return statements;
  }

  private Stmt ifStatement() {
    consume(LEFT_PAREN, "Expected '(' after 'if'.");
    Expr condition = expression();
    consume(RIGHT_PAREN, "Expected ')' after 'if' condition.");

    Stmt thenBranch = statement();
    Stmt elseBranch = null;
    if (match(ELSE)) {
      elseBranch = statement();
    }

    return new Stmt.If(condition, thenBranch, elseBranch);
  }

  private Stmt whileStatement() {
    loopCounter += 1;

    consume(LEFT_PAREN, "Expected '(' after 'while'.");
    Expr condition = expression();
    consume(RIGHT_PAREN, "Expected ')' after 'while' condition.");
    Stmt body = statement();

    loopCounter -= 1;

    return new Stmt.While(condition, body);
  }

  private Stmt forStatement() {
    loopCounter += 1;

    consume(LEFT_PAREN, "Expected '(' after 'for'.");

    Stmt initializer;
    if (match(SEMICOLON)) {
      initializer = null;
    } else if (match(VAR)) {
      initializer = varDeclaration();
    } else {
      initializer = expressionStatement();
    }

    Expr condition = null;
    if (!check(SEMICOLON)) {
      condition = expression();
    }
    consume(SEMICOLON, "Expected ';' after condition clause in for loop.");

    Expr increment = null;
    if (!check(RIGHT_PAREN)) {
      increment = expression();
    }
    consume(RIGHT_PAREN, "Expected ')' after for clauses.");

    Stmt body = statement();

    if (increment != null) {
      body = new Stmt.Block(Arrays.asList(
        body,
        new Stmt.Expression(increment)
      ));
    }

    if (condition == null) condition = new Expr.Literal(true);
    body = new Stmt.While(condition, body);

    if (initializer != null) {
      body = new Stmt.Block(Arrays.asList(initializer, body));
    }
    
    loopCounter -= 1;

    return body;
  }

  private Stmt breakStatement() {
    Token brk = previous();

    if (loopCounter == 0) {
      throw error(brk, "Break statement must appear inside of loop.");
    }

    consume(SEMICOLON, "Expect ';' after break statement.");
    return new Stmt.Break(brk);
  }

  private Stmt returnStatement() {
    Token keyword = previous();

    Expr value = null;
    if (!check(SEMICOLON)) {
      value = expression();
    }

    consume(SEMICOLON, "Expect ';' after return statement.");
    return new Stmt.Return(keyword, value);
  }

  private Stmt expressionStatement() {
    Expr expr = expression();
    consume(SEMICOLON, "Expect ';' after expression.");
    return new Stmt.Expression(expr);
  }

  public Expr expression() {
    return assignment();
  }

  private Expr assignment() {
    Expr expr = ternary();

    if (match(EQUAL)) {
      Token equals = previous();
      Expr value = assignment();
      
      if (expr instanceof Expr.Variable) {
        Token name = ((Expr.Variable)expr).name;
        return new Expr.Assign(name, value);
      }
      
      error(equals, "Invalid assignment target");
    }

    return expr;
  }

  private Expr ternary() {
    Expr expr = or();

    if (match(QUESTION)) {
      Expr ifTrue = ternary();
      consume(COLON, "Expected ':' in ternary operator.");
      Expr ifFalse = ternary();

      return new Expr.Ternary(expr, ifTrue, ifFalse);
    }

    return expr;
  }

  private Expr or() {
    Expr expr = and();

    while (match(OR)) {
      Token operator = previous();
      Expr right = and();
      expr = new Expr.Logical(expr, operator, right);
    }

    return expr;
  }

  private Expr and() {
    Expr expr = equality();

    while (match(AND)) {
      Token operator = previous();
      Expr right = equality();
      expr = new Expr.Logical(expr, operator, right);
    }

    return expr;
  }

  private Expr equality() {
    Expr expr = comparison();

    while (match(BANG_EQUAL, EQUAL_EQUAL)) {
      Token operator = previous();
      Expr right = comparison();
      expr = new Expr.Binary(expr, operator, right);
    }

    return expr;
  }

  private Expr comparison() {
    Expr expr = term();

    while (match(LESS, LESS_EQUAL, GREATER, GREATER_EQUAL)) {
      Token operator = previous();
      Expr right = term();
      expr = new Expr.Binary(expr, operator, right);
    }

    return expr;
  }

  private Expr term() {
    Expr expr = factor();

    while (match(PLUS, MINUS)) {
      Token operator = previous();
      Expr right = factor();
      expr = new Expr.Binary(expr, operator, right);
    }

    return expr;
  }

  private Expr factor() {
    Expr expr = unary();

    while(match(SLASH, STAR)) {
      Token operator = previous();
      Expr right = unary();
      expr = new Expr.Binary(expr, operator, right);
    }

    return expr;
  }

  private Expr unary() {
    if (match(MINUS, BANG)) {
      Token operator = previous();
      Expr right = unary();
      return new Expr.Unary(operator, right);
    }

    return call();
  }

  private Expr call() {
    Expr expr = lambda();

    while (true) {
      if (match(LEFT_PAREN)) {
        expr = finishCall(expr);
      } else {
        break;
      }
    }

    return expr;
  }

  private Expr lambda() {
    if (match(FUN)) {
      List<Token> params = parameters("lambda");
      List<Stmt> body = body("lambda");
      return new Expr.Function(params, body);
    }

    return primary();
  }

  private Expr primary() {
    if (match(TRUE)) return new Expr.Literal(true);
    if (match(FALSE)) return new Expr.Literal(false);
    if (match(NIL)) return new Expr.Literal(null);

    if (match(NUMBER, STRING)) {
      return new Expr.Literal(previous().literal);
    }

    if (match(LEFT_PAREN)) {
      Expr expr = expression();
      consume(RIGHT_PAREN, "Expect ')' after expression.");
      return new Expr.Grouping(expr);
    }

    if (match(IDENTIFIER)) {
      return new Expr.Variable(previous());
    }

    throw error(peek(), "Expect expression.");
  }

  private Expr finishCall(Expr expr) {
    List<Expr> arguments = new ArrayList<>();
    if (!check(RIGHT_PAREN)) {
      do {
        if (arguments.size() >= 255) {
          error(peek(), "Can't have more than 255 arguments.");
        }
        arguments.add(expression());
      } while (match(COMMA));
    }

    Token paren = consume(RIGHT_PAREN, "Function call must be closed out by a ')'");
    return new Expr.Call(expr, paren, arguments);
  }

  private boolean match(TokenType... types) {
    for (TokenType type : types) {
      if (check(type)) {
        advance();
        return true;
      }
    }

    return false;
  }

  private boolean check(TokenType type) {
    if (isAtEnd()) return false;
    return peek().type == type;
  }

  private boolean checkNext(TokenType type) {
    if (isAtEnd() || isNextAtEnd()) return false;
    return peekNext().type == type;
  }

  private boolean isAtEnd() {
    return peek().type == EOF;
  }

  private boolean isNextAtEnd() {
    return peekNext().type == EOF;
  }

  private Token peek() {
    return tokens.get(current);
  }

  private Token peekNext() {
    return tokens.get(current + 1);
  }

  private Token advance() {
    if (!isAtEnd()) current++;
    return previous();
  }

  private Token previous() {
    return tokens.get(current - 1);
  }

  private Token consume(TokenType type, String message) {
    if (check(type)) return advance();

    throw error(peek(), message);
  }

  private ParseError error (Token token, String message) {
    Lox.error(token, message);
    return new ParseError();
  }

  private void synchronize() {
    advance();

    while (!isAtEnd()) {
      if (previous().type == SEMICOLON) return;

      switch (peek().type) {
        case CLASS:
        case FUN:
        case VAR:
        case FOR:
        case IF:
        case WHILE:
        case PRINT:
        case RETURN:
          return;
      }

      advance();
    }
  }
}
