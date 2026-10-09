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

import java.lang.reflect.Method;

/**
 * JVM method descriptors (JVMS 4.3.3) computed from reflection and read back. Stateless; safe from any thread.
 */
final class Descriptors {

    private Descriptors() {
    }

    /** The descriptor of a method, {@code (Ljava/lang/String;I)Ljava/math/BigDecimal;}. */
    static String of(Method method) {
        StringBuilder out = new StringBuilder("(");
        for (Class<?> p : method.getParameterTypes()) {
            out.append(of(p));
        }
        return out.append(')').append(of(method.getReturnType())).toString();
    }

    /** The field descriptor of a type. */
    static String of(Class<?> type) {
        if (type.isArray()) {
            return type.getName().replace('.', '/');
        }
        if (type.isPrimitive()) {
            if (type == void.class) {
                return "V";
            }
            if (type == boolean.class) {
                return "Z";
            }
            if (type == byte.class) {
                return "B";
            }
            if (type == char.class) {
                return "C";
            }
            if (type == short.class) {
                return "S";
            }
            if (type == int.class) {
                return "I";
            }
            if (type == long.class) {
                return "J";
            }
            if (type == float.class) {
                return "F";
            }
            return "D";
        }
        return "L" + type.getName().replace('.', '/') + ";";
    }

    /** How many parameters a well-formed method descriptor declares. */
    static int parameterCount(String descriptor) {
        int count = 0;
        int i = 1;
        while (i < descriptor.length() && descriptor.charAt(i) != ')') {
            while (descriptor.charAt(i) == '[') {
                i++;
            }
            if (descriptor.charAt(i) == 'L') {
                i = descriptor.indexOf(';', i);
            }
            i++;
            count++;
        }
        return count;
    }

    /** The readable name of a field descriptor: {@code int}, {@code java.math.BigDecimal}, {@code int[]}. */
    static String typeName(String fieldDescriptor) {
        int dims = 0;
        while (dims < fieldDescriptor.length() && fieldDescriptor.charAt(dims) == '[') {
            dims++;
        }
        String element = fieldDescriptor.substring(dims);
        String name;
        switch (element.charAt(0)) {
            case 'V':
                name = "void";
                break;
            case 'Z':
                name = "boolean";
                break;
            case 'B':
                name = "byte";
                break;
            case 'C':
                name = "char";
                break;
            case 'S':
                name = "short";
                break;
            case 'I':
                name = "int";
                break;
            case 'J':
                name = "long";
                break;
            case 'F':
                name = "float";
                break;
            case 'D':
                name = "double";
                break;
            default:
                name = element.substring(1, element.length() - 1).replace('/', '.');
        }
        StringBuilder out = new StringBuilder(name);
        for (int d = 0; d < dims; d++) {
            out.append("[]");
        }
        return out.toString();
    }
}
