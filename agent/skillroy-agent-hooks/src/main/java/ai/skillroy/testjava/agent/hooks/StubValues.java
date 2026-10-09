/*
 * Copyright 2026 Mike Mills.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package ai.skillroy.testjava.agent.hooks;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * The conversion of a stub value, once, when it is armed (phase6-contracts.md section 3.4): a value of the wrong type
 * would make the hooked method return its type's default silently (BS3 RESULT section 9 fact 5), so it is never left
 * to the advice. JSON {@code null}, booleans, numbers and strings become the method's declared return type: a primitive
 * or its box, {@code String}, {@code BigDecimal} and {@code BigInteger} (from a string or a number), an enum by name;
 * any other return type accepts only {@code null}; a {@code null} for a primitive is refused; a {@code void} method
 * accepts no value (skip only).
 *
 * <p>Stateless; safe from any thread.
 */
final class StubValues {

    private StubValues() {
    }

    /**
     * @return the value of the site's return type (boxed for a primitive)
     * @throws IllegalArgumentException with the reason, when the value cannot be converted
     */
    static Object convert(Object json, Site site) {
        String rd = site.returnDescriptor;
        if (site.isVoid) {
            if (json != null) {
                throw new IllegalArgumentException("a void method is stubbed without a value (the body is skipped)");
            }
            return null;
        }
        if (rd.length() == 1) {
            if (json == null) {
                throw new IllegalArgumentException("null cannot stand for the primitive " + site.returnTypeName());
            }
            return primitive(json, rd.charAt(0));
        }
        if (json == null) {
            return null;
        }
        String type = site.returnTypeName();
        if ("java.lang.String".equals(type)) {
            if (json instanceof String || json instanceof Number || json instanceof Boolean) {
                return String.valueOf(json);
            }
        } else if ("java.math.BigDecimal".equals(type)) {
            return new BigDecimal(text(json));
        } else if ("java.math.BigInteger".equals(type)) {
            return new BigInteger(text(json));
        } else if ("java.lang.Boolean".equals(type)) {
            return primitive(json, 'Z');
        } else if ("java.lang.Byte".equals(type)) {
            return primitive(json, 'B');
        } else if ("java.lang.Short".equals(type)) {
            return primitive(json, 'S');
        } else if ("java.lang.Integer".equals(type)) {
            return primitive(json, 'I');
        } else if ("java.lang.Long".equals(type)) {
            return primitive(json, 'J');
        } else if ("java.lang.Float".equals(type)) {
            return primitive(json, 'F');
        } else if ("java.lang.Double".equals(type)) {
            return primitive(json, 'D');
        } else if ("java.lang.Character".equals(type)) {
            return primitive(json, 'C');
        } else {
            Class<?> returnType = site.returnType.get();
            if (returnType != null && returnType.isEnum() && json instanceof String) {
                return enumConstant(returnType, (String) json);
            }
            throw new IllegalArgumentException("a method returning " + type + " can only be stubbed with null");
        }
        throw new IllegalArgumentException("cannot convert " + kind(json) + " to " + type);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> type, String name) {
        try {
            return Enum.valueOf((Class) type, name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("no constant " + name + " in " + type.getName());
        }
    }

    private static Object primitive(Object json, char kind) {
        switch (kind) {
            case 'Z':
                if (json instanceof Boolean) {
                    return json;
                }
                if ("true".equals(json) || "false".equals(json)) {
                    return Boolean.valueOf((String) json);
                }
                throw new IllegalArgumentException("cannot convert " + kind(json) + " to boolean");
            case 'C':
                if (json instanceof String && ((String) json).length() == 1) {
                    return Character.valueOf(((String) json).charAt(0));
                }
                throw new IllegalArgumentException("a char is stubbed with a string of one character");
            case 'F':
                return Float.valueOf(new BigDecimal(text(json)).floatValue());
            case 'D':
                return Double.valueOf(new BigDecimal(text(json)).doubleValue());
            default:
                BigDecimal d = new BigDecimal(text(json));
                try {
                    long l = d.longValueExact();
                    switch (kind) {
                        case 'B':
                            if (l < Byte.MIN_VALUE || l > Byte.MAX_VALUE) {
                                break;
                            }
                            return Byte.valueOf((byte) l);
                        case 'S':
                            if (l < Short.MIN_VALUE || l > Short.MAX_VALUE) {
                                break;
                            }
                            return Short.valueOf((short) l);
                        case 'I':
                            if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
                                break;
                            }
                            return Integer.valueOf((int) l);
                        default:
                            return Long.valueOf(l);
                    }
                } catch (ArithmeticException notIntegral) {
                    throw new IllegalArgumentException("not an integer: " + text(json));
                }
                throw new IllegalArgumentException("out of range for " + Descriptors.typeName(String.valueOf(kind))
                        + ": " + text(json));
        }
    }

    private static String text(Object json) {
        if (json instanceof Number || json instanceof String) {
            String text = String.valueOf(json).trim();
            if (text.length() > 64) {
                throw new IllegalArgumentException("a numeric stub value is at most 64 characters");
            }
            try {
                new BigDecimal(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not a number: " + text);
            }
            return text;
        }
        throw new IllegalArgumentException("cannot convert " + kind(json) + " to a number");
    }

    private static String kind(Object json) {
        return json == null ? "null" : json instanceof String ? "a string" : json instanceof Boolean ? "a boolean"
                : json instanceof Number ? "a number" : json instanceof java.util.Map ? "an object"
                : json instanceof java.util.List ? "an array" : json.getClass().getSimpleName();
    }
}
