/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package com.palantir.conjure.parser.types;

import com.palantir.conjure.parser.ConjureMetrics;
import com.palantir.conjure.parser.types.builtin.AnyType;
import com.palantir.conjure.parser.types.builtin.BinaryType;
import com.palantir.conjure.parser.types.builtin.DateTimeType;
import com.palantir.conjure.parser.types.collect.ListType;
import com.palantir.conjure.parser.types.collect.MapType;
import com.palantir.conjure.parser.types.collect.OptionalType;
import com.palantir.conjure.parser.types.collect.SetType;
import com.palantir.conjure.parser.types.names.Namespace;
import com.palantir.conjure.parser.types.names.TypeName;
import com.palantir.conjure.parser.types.reference.ForeignReferenceType;
import com.palantir.conjure.parser.types.reference.LocalReferenceType;
import com.palantir.parsec.ParseException;
import com.palantir.parsec.Parser;
import com.palantir.parsec.ParserState;
import com.palantir.parsec.Parsers;
import com.palantir.parsec.StringParserState;
import com.palantir.parsec.parsers.ExpectationResult;
import com.palantir.parsec.parsers.KeyValueParser;
import com.palantir.parsec.parsers.KeyValueParser.KeyValue;
import com.palantir.parsec.parsers.RawStringParser;

public enum TypeParser implements Parser<ConjureType> {
    INSTANCE;

    public ConjureType parse(String input) throws ParseException {
        ParserState inputParserState = new StringParserState(input);
        ConjureType resultType = Parsers.eof(typeParser()).parse(inputParserState);
        if (resultType == null) {
            throw new ParseException(input, inputParserState);
        }
        return parse(new StringParserState(input));
    }

    @Override
    public ConjureType parse(ParserState input) throws ParseException {
        return typeParser().parse(input);
    }

    private Parser<ConjureType> typeParser() {
        return Parsers.or(
                MapTypeParser.INSTANCE,
                ListTypeParser.INSTANCE,
                SetTypeParser.INSTANCE,
                OptionalTypeParser.INSTANCE,
                TypeFromString.of("any", AnyType.of(), AnyType.class),
                TypeFromString.of("binary", BinaryType.of(), BinaryType.class),
                TypeFromString.of("datetime", DateTimeType.of(), DateTimeType.class),
                ForeignReferenceTypeParser.INSTANCE,
                TypeReferenceParser.INSTANCE);
    }

    private enum TypeReferenceParser implements Parser<LocalReferenceType> {
        INSTANCE;

        public static final Parser<String> REF_PARSER = new RawStringParser(
                new RawStringParser.AllowableCharacters() {
                    @Override
                    public boolean isAllowed(char character) {
                        return Character.isJavaIdentifierPart(character);
                    }

                    @Override
                    public String getDescription() {
                        return "Character is an allowable Java identifier character";
                    }
                });

        @Override
        public LocalReferenceType parse(ParserState input) throws ParseException {
            input.mark();
            String typeReference = REF_PARSER.parse(input);
            if (typeReference == null) {
                input.rewind();
                return null;
            }
            input.release();
            ConjureMetrics.incrementCounter(LocalReferenceType.class);
            return LocalReferenceType.of(TypeName.of(typeReference));
        }
    }

    private enum ForeignReferenceTypeParser implements Parser<ForeignReferenceType> {
        INSTANCE;

        public static final Parser<String> NAMESPACE_PARSER = new RawStringParser(
                new RawStringParser.AllowableCharacters() {
                    @Override
                    public boolean isAllowed(char character) {
                        return ('a' <= character && character <= 'z')
                                || ('A' <= character && character <= 'Z');
                    }

                    @Override
                    public String getDescription() {
                        return "Character is one of [a-zA-Z]";
                    }
                });

        @Override
        public ForeignReferenceType parse(ParserState input) throws ParseException {
            String namespace = NAMESPACE_PARSER.parse(input);
            if (Parsers.nullOrUnexpected(Parsers.expect(".").parse(input))) {
                return null;
            }
            String ref = TypeReferenceParser.REF_PARSER.parse(input);
            ConjureMetrics.incrementCounter(ForeignReferenceType.class);
            return ForeignReferenceType.of(Namespace.of(namespace), TypeName.of(ref));
        }
    }

    private enum ListTypeParser implements Parser<ListType> {
        INSTANCE;

        @Override
        public ListType parse(ParserState input) throws ParseException {
            ExpectationResult result = Parsers.expect("list").parse(input);
            if (Parsers.nullOrUnexpected(result)) {
                return null;
            }

            ConjureType itemType = Parsers.liberalBetween("<", TypeParser.INSTANCE, ">").parse(input);
            ConjureMetrics.incrementCounter(ListType.class);
            return ListType.of(itemType);
        }
    }

    private enum SetTypeParser implements Parser<SetType> {
        INSTANCE;

        @Override
        public SetType parse(ParserState input) throws ParseException {
            ExpectationResult result = Parsers.expect("set").parse(input);
            if (Parsers.nullOrUnexpected(result)) {
                return null;
            }

            ConjureType itemType = Parsers.liberalBetween("<", TypeParser.INSTANCE, ">").parse(input);
            ConjureMetrics.incrementCounter(SetType.class);
            return SetType.of(itemType);
        }
    }

    private enum OptionalTypeParser implements Parser<OptionalType> {
        INSTANCE;

        @Override
        public OptionalType parse(ParserState input) throws ParseException {
            ExpectationResult result = Parsers.expect("optional").parse(input);
            if (Parsers.nullOrUnexpected(result)) {
                return null;
            }

            ConjureType itemType = Parsers.liberalBetween("<", TypeParser.INSTANCE, ">").parse(input);
            ConjureMetrics.incrementCounter(OptionalType.class);
            return OptionalType.of(itemType);
        }
    }

    private enum MapTypeParser implements Parser<MapType> {
        INSTANCE;

        @Override
        public MapType parse(ParserState input) throws ParseException {
            ExpectationResult result = Parsers.expect("map").parse(input);
            if (Parsers.nullOrUnexpected(result)) {
                return null;
            }

            Parser<KeyValue<ConjureType, ConjureType>> kv = Parsers.liberalBetween(
                    "<",
                    new KeyValueParser<>(
                            Parsers.whitespace(TypeParser.INSTANCE),
                            Parsers.whitespace(Parsers.expect(",")),
                            Parsers.whitespace(TypeParser.INSTANCE)),
                    ">");

            KeyValue<ConjureType, ConjureType> types = kv.parse(input);
            ConjureMetrics.incrementCounter(MapType.class);
            return MapType.of(types.getKey(), types.getValue());
        }
    }

    private static final class TypeFromString<T> implements Parser<T> {
        private final String type;
        private final T instance;
        private final Class<T> metric;

        TypeFromString(String type, T instance, Class<T> metric) {
            this.type = type;
            this.instance = instance;
            this.metric = metric;
        }

        @Override
        public T parse(ParserState input) throws ParseException {
            ExpectationResult result = Parsers.expect(type).parse(input);
            if (Parsers.nullOrUnexpected(result)) {
                return null;
            }

            ConjureMetrics.incrementCounter(metric);
            return instance;
        }

        public static <T> TypeFromString<T> of(String type, T instance, Class<T> metric) {
            return new TypeFromString<>(type, instance, metric);
        }
    }
}