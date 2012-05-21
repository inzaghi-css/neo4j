/**
 * Copyright (c) 2002-2012 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.parser.v1_8

import org.neo4j.cypher.internal.commands._
import org.neo4j.graphdb.Direction
import org.neo4j.cypher.SyntaxException


trait Expressions extends Base {
  def expression: Parser[Expression] = term ~ rep( "+" ~ term | "-" ~ term) ^^ {
    case head ~ rest => {
      var result = head
      rest.foreach {
        case "+" ~ f => result = Add(result, f)
        case "-" ~ f => result = Subtract(result, f)
      }

      result
    }
  }

  def term: Parser[Expression] = factor ~ rep("*" ~ factor | "/" ~ factor | "%" ~ factor | "^" ~ factor) ^^ {
    case head ~ rest => {
      var result = head
      rest.foreach {
        case "*" ~ f => result = Multiply(result,f)
        case "/" ~ f => result = Divide(result,f)
        case "%" ~ f => result = Modulo(result,f)
        case "^" ~ f => result = Pow(result,f)
      }

      result
    }
  }

  def factor: Parser[Expression] =
    ( ignoreCase("true")    ^^^ Literal(true)
      | ignoreCase("false") ^^^ Literal(false)
      | ignoreCase("null")  ^^^ Literal(null)
      | extract
      | function
      | aggregateExpression
      | identity~>parens(expression | entity)~>failure("unknown function")
      | coalesceFunc
      | filterFunc
      | nullableProperty
      | property
      | stringLit
      | numberLiteral
      | collectionLiteral
      | parameter
      | entity
      | parens(expression)
      | failure("illegal start of value") )


  def stringLit:Parser[Expression] = string ^^ (x=>Literal(x))

  def numberLiteral:Parser[Expression] = number ^^ (x => {
    val value:Any = if(x.contains("."))
      x.toDouble
    else
      x.toLong

    Literal(value)
  } )

  def entity: Parser[Entity] = identity ^^ (x => Entity(x))

  def collectionLiteral:Parser[Expression] = "[" ~> repsep(expression, ",") <~ "]" ^^ (seq => Collection(seq:_*))

  def property: Parser[Expression] = identity ~ "." ~ identity ^^ {
    case v ~ "." ~ p => createProperty(v, p)
  }

  def createProperty(entity:String, propName:String):Expression

  trait DefaultTrue
  trait DefaultFalse

  def nullableProperty: Parser[Expression] = (
    property <~ "?" ^^ (p => new Nullable(p) with DefaultTrue) |
    property <~ "!" ^^ (p => new Nullable(p) with DefaultFalse))

  def extract: Parser[Expression] = ignoreCase("extract") ~> parens(identity ~ ignoreCase("in") ~ expression ~ ":" ~ expression) ^^ {
    case (id ~ in ~ iter ~ ":" ~ expression) => ExtractFunction(iter, id, expression)
  }

  def coalesceFunc: Parser[Expression] = ignoreCase("coalesce") ~> parens(commaList(expression)) ^^ {
    case expressions => CoalesceFunction(expressions: _*)
  }

  def filterFunc: Parser[Expression] = ignoreCase("filter") ~> parens(identity ~ ignoreCase("in") ~ expression ~ (ignoreCase("where") | ":") ~ predicate) ^^ {
    case symbol ~ in ~ collection ~ where ~ pred => FilterFunction(collection, symbol, pred)
  }

  def function: Parser[Expression] = Parser {
    case in => {
      val inner = identity ~ parens(commaList(expression | entity))

      inner(in) match {

        case Success(name ~ args, rest) => functions.get(name.toLowerCase) match {
          case None => Failure("No such function found", rest)
          case Some(func) if !func.acceptsTheseManyArguments(args.size) => Failure("Wrong number of parameters for function " + name, rest)
          case Some(func) => Success(func.create(args), rest)
        }

        case Failure(msg, rest) => Failure(msg, rest)
        case Error(msg, rest) => Error(msg, rest)
      }
    }
  }


  private def func(numberOfArguments: Int, create: List[Expression] => Expression) = new Function(x => x == numberOfArguments, create)
  case class Function(acceptsTheseManyArguments: Int => Boolean, create: List[Expression] => Expression)
  val functions = Map(
    "type" -> func(1, args => RelationshipTypeFunction(args.head)),
    "id" -> func(1, args => IdFunction(args.head)),
    "length" -> func(1, args => LengthFunction(args.head)),
    "nodes" -> func(1, args => NodesFunction(args.head)),
    "rels" -> func(1, args => RelationshipFunction(args.head)),
    "relationships" -> func(1, args => RelationshipFunction(args.head)),
    "abs" -> func(1, args => AbsFunction(args.head)),
    "round" -> func(1, args => RoundFunction(args.head)),
    "sqrt" -> func(1, args => SqrtFunction(args.head)),
    "sign" -> func(1, args => SignFunction(args.head)),
    "head" -> func(1, args => HeadFunction(args.head)),
    "last" -> func(1, args => LastFunction(args.head)),
    "tail" -> func(1, args => TailFunction(args.head)),
    "range" -> Function(x => x == 2||x == 3, args => {
      val step = if (args.size == 2) Literal(1) else args(2)
      RangeFunction(args(0), args(1), step)
    })
  )

  def aggregateExpression: Parser[Expression] = countStar | aggregationFunction

  def aggregateFunctionNames = ignoreCases("count", "sum", "min", "max", "avg", "collect")
  def aggregationFunction: Parser[Expression] = aggregateFunctionNames ~ parens(opt(ignoreCase("distinct")) ~ expression) ^^ {
    case function ~ (distinct ~ inner) => {

      val aggregateExpression = function match {
        case "count" => Count(inner)
        case "sum" => Sum(inner)
        case "min" => Min(inner)
        case "max" => Max(inner)
        case "avg" => Avg(inner)
        case "collect" => Collect(inner)
      }

      if (distinct.isEmpty) {
        aggregateExpression
      }
      else {
        Distinct(aggregateExpression, inner)
      }
    }
  }

  def countStar: Parser[Expression] = ignoreCase("count") ~> parens("*") ^^^ CountStar()

  def predicate: Parser[Predicate] = predicateLvl1 ~ rep( ignoreCase("or") ~> predicateLvl1 ) ^^ {
    case head ~ rest => rest.foldLeft(head)((a,b) => Or(a,b))
  }

  def predicateLvl1: Parser[Predicate] = predicateLvl2 ~ rep( ignoreCase("and") ~> predicateLvl2 ) ^^{
    case head ~ rest => rest.foldLeft(head)((a,b) => And(a,b))
  }

  def predicateLvl2: Parser[Predicate] = (
    expressionOrEntity <~ ignoreCase("is null") ^^ (x => IsNull(x))
      | expressionOrEntity <~ ignoreCase("is not null") ^^ (x => Not(IsNull(x)))
      | operators
      | ignoreCase("not") ~> predicate ^^ ( inner => Not(inner) )
      | ignoreCase("has") ~> parens(property) ^^ ( prop => Has(prop.asInstanceOf[Property]))
      | parens(predicate)
      | sequencePredicate
      | hasRelationshipTo
      | hasRelationship
      | aggregateFunctionNames ~> parens(expression) ~> failure("aggregate functions can not be used in the WHERE clause")
    )

  def sequencePredicate: Parser[Predicate] = allInSeq | anyInSeq | noneInSeq | singleInSeq | in

  def symbolIterablePredicate: Parser[(Expression, String, Predicate)] =
    (identity ~ ignoreCase("in") ~ expression ~ ignoreCase("where")  ~ predicate ^^ { case symbol ~ in ~ iterable ~ where ~ klas => (iterable, symbol, klas) }
      |identity ~> ignoreCase("in") ~ expression ~> failure("expected where"))

  def in : Parser[Predicate] = expression ~ ignoreCase("in") ~ expression ^^ {
    case checkee ~ in ~ collection => AnyInIterable(collection, "-_-INNER-_-", Equals(checkee, Entity("-_-INNER-_-")))
  }

  def allInSeq: Parser[Predicate] = ignoreCase("all") ~> parens(symbolIterablePredicate) ^^ (x => AllInIterable(x._1, x._2, x._3))

  def anyInSeq: Parser[Predicate] = ignoreCase("any") ~> parens(symbolIterablePredicate) ^^ (x => AnyInIterable(x._1, x._2, x._3))

  def noneInSeq: Parser[Predicate] = ignoreCase("none") ~> parens(symbolIterablePredicate) ^^ (x => NoneInIterable(x._1, x._2, x._3))

  def singleInSeq: Parser[Predicate] = ignoreCase("single") ~> parens(symbolIterablePredicate) ^^ (x => SingleInIterable(x._1, x._2, x._3))

  def operators:Parser[Predicate] =
    (expression ~ "=" ~ expression ^^ { case l ~ "=" ~ r => nullable(Equals(l, r),l,r)  } |
      expression ~ ("<"~">") ~ expression ^^ { case l ~ wut ~ r => nullable(Not(Equals(l, r)),l,r) } |
      expression ~ "<" ~ expression ^^ { case l ~ "<" ~ r => nullable(LessThan(l, r),l,r) } |
      expression ~ ">" ~ expression ^^ { case l ~ ">" ~ r => nullable(GreaterThan(l, r),l,r) } |
      expression ~ "<=" ~ expression ^^ { case l ~ "<=" ~ r => nullable(LessThanOrEqual(l, r),l,r) } |
      expression ~ ">=" ~ expression ^^ { case l ~ ">=" ~ r => nullable(GreaterThanOrEqual(l, r),l,r) } |
      expression ~ "=~" ~ regularLiteral ^^ { case a ~ "=~" ~ b => nullable(LiteralRegularExpression(a, b),a,b) } |
      expression ~ "=~" ~ expression ^^ { case a ~ "=~" ~ b => nullable(RegularExpression(a, b),a,b) } |
      expression ~> "!" ~> failure("The exclamation symbol is used as a nullable property operator in Cypher. The 'not equal to' operator is <>"))

  private def nullable(pred:Predicate, e:Expression*):Predicate = if(!e.exists(_.isInstanceOf[Nullable]))
    pred
  else
  {
    val map = e.filter(x => x.isInstanceOf[Nullable]).
      map( x => (x, x.isInstanceOf[DefaultTrue]))

    NullablePredicate(pred, map  )
  }

  def expressionOrEntity = expression | entity

  def hasRelationshipTo: Parser[Predicate] = expressionOrEntity ~ relInfo ~ expressionOrEntity ^^ { case a ~ rel ~ b => HasRelationshipTo(a, b, rel._1, rel._2) }

  def hasRelationship: Parser[Predicate] = expressionOrEntity ~ relInfo <~ "()" ^^ { case a ~ rel  => HasRelationship(a, rel._1, rel._2) }

  def relInfo: Parser[(Direction, Seq[String])] = opt("<") ~ "-" ~ opt("[:" ~> rep1sep(identity, "|") <~ "]") ~ "-" ~ opt(">") ^^ {
    case Some("<") ~ "-" ~ relType ~ "-" ~ Some(">") => throw new SyntaxException("Can't be connected both ways.", "query", 666)
    case Some("<") ~ "-" ~ relType ~ "-" ~ None => (Direction.INCOMING, relType.toSeq.flatten)
    case None ~ "-" ~ relType ~ "-" ~ Some(">") => (Direction.OUTGOING, relType.toSeq.flatten)
    case None ~ "-" ~ relType ~ "-" ~ None => (Direction.BOTH, relType.toSeq.flatten)
  }

}











