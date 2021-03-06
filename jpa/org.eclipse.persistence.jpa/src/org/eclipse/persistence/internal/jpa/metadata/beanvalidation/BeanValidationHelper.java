/*******************************************************************************
 * Copyright (c) 2015 Oracle and/or its affiliates. All rights reserved.
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 and Eclipse Distribution License v. 1.0
 * which accompanies this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * Contributors:
 *     Marcel Valovy - marcelv3612@gmail.com - initial API and implementation
 ******************************************************************************/
package org.eclipse.persistence.internal.jpa.metadata.beanvalidation;

import org.eclipse.persistence.internal.cache.AdvancedProcessor;
import org.eclipse.persistence.internal.cache.ComputableTask;
import org.eclipse.persistence.internal.security.PrivilegedAccessHelper;

import javax.validation.Constraint;
import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * INTERNAL:
 *
 * Utility class for bean validation related tasks.
 *  - Singleton.
 *  - Thread-safe.
 *
 * @author Marcel Valovy - marcel.valovy@oracle.com
 * @since 2.6
 */
public enum BeanValidationHelper {
    BEAN_VALIDATION_HELPER;

    /**
     * # default constraints in {@link #KNOWN_CONSTRAINTS} map.
     *
     * The value reflects the number of default constraints; if
     * more default constraints are added, update the value.
     */
    private static final int DEFAULT_CONSTRAINTS_QUANTITY = 25;

    /**
     * Load factor for concurrent maps.
     */
    private static final float LOAD_FACTOR = 0.75f;

    /**
     * Size parameter for {@link #KNOWN_CONSTRAINTS} map.
     */
    private static final int KNOWN_CONSTRAINTS_DEFAULT_SIZE = nextPowerOfTwo(
            (int) (DEFAULT_CONSTRAINTS_QUANTITY / LOAD_FACTOR));

    /**
     * How much time to allow {@link ValidationXMLReader#runAsynchronously()} to finish its task, in milliseconds.
     * This is an interrupt management system. If the asynchronous thread were interrupted and flag {@link
     * ValidationXMLReader#firstTime} were to be false, deadlock would occur. This prevents such case, as it will
     * await until timeout expires and then run the processing in forced, synchronous way.
     */
    private static final int TIMEOUT = 10; /* in ms, handles precision up to single digits since nanoTime is used. */

    /**
     * Speed-up flag. Indicates that parsing of validation.xml file has finished.
     */
    private static boolean xmlParsed = false;

    /**
     * Advanced memoizer.
     */
    private final AdvancedProcessor memoizer = new AdvancedProcessor();

    /**
     * Computable task for memoizer.
     */
    private final ConstraintsDetectorService<Class<?>, Boolean> cds = new ConstraintsDetectorService<>();

    /**
     * Set of all default BeanValidation field annotations and discovered custom field constraints.
     * Implemented as a ConcurrentHashMap with "Null Object" idiom.
     */
    private static final Set<String> KNOWN_CONSTRAINTS = Collections.newSetFromMap(new ConcurrentHashMap<>( 
            KNOWN_CONSTRAINTS_DEFAULT_SIZE, LOAD_FACTOR ));

    /**
     * Map of all classes that have undergone check for bean validation constraints.
     * Maps the key with boolean value telling whether the class contains an annotation from {@link #KNOWN_CONSTRAINTS}.
     */
    private static final Map<Class<?>, Boolean> CONSTRAINTS_ON_CLASSES = Collections.synchronizedMap(new
            WeakHashMap<>());

    static {
        initializeKnownConstraints();
        ValidationXMLReader.runAsynchronously();
    }

    /**
     * Put a class to the map of constrained classes with value Boolean.TRUE. Specifying value is not allowed because
     * there is nothing to detect that would make class not constrained after it was already found to be constrained.
     *
     * @return value previously associated with the class or null if the class was not in dictionary before
     */
    Boolean putConstrainedClass(Class<?> clazz) {
        return CONSTRAINTS_ON_CLASSES.put(clazz, Boolean.TRUE);
    }

    /**
     * Tells whether any of the class's {@link java.lang.reflect.AccessibleObject}s are constrained by Bean
     * Validation annotations or custom constraints.
     *
     * @param clazz checked class
     * @return true or false
     */
    public boolean isConstrained(Class<?> clazz) {
        if (!xmlParsed) ensureValidationXmlWasParsed();

        return memoizer.compute(cds, clazz);
    }

    /**
     * INTERNAL:
     * Ensures that validation.xml was parsed and classes described externally were added to {@link
     * #CONSTRAINTS_ON_CLASSES}.
     * Strategy:
     *  - if {@link ValidationXMLReader#runAsynchronously()} is not doing anything,
     *  run parsing synchronously.
     *  - else if latch count is 0, indicating successful finish of asynchronous processing, return.
     *  - else wait for {@link #TIMEOUT} seconds to allow async thread to finish. If it does not finish till then,
     *  run parsing synchronously.
     *
     *  Note: Run parsing synchronously is force of last resort. If that fails, we proceed with validation and do not
     *  account for classes specified in validation.xml - there is high chance that if we cannot read validation.xml
     *  successfully, neither will be able the Validation implementation.
     */
    private void ensureValidationXmlWasParsed() {
        // loop handles InterruptedException
        while (!xmlParsed) {
            try {
                if (ValidationXMLReader.asyncAttemptFailed()
                        || !ValidationXMLReader.getLatch().await(TIMEOUT, TimeUnit.MILLISECONDS)) {
                    ValidationXMLReader.runSynchronouslyForced();
                }
                xmlParsed = true;
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @SuppressWarnings("unchecked")
    public class ConstraintsDetectorService<A, V> implements ComputableTask<A, V> {

        @Override
        public V compute(A arg) throws InterruptedException {
            Boolean b = isConstrained0((Class<?>) arg);
            return (V) b;
        }

        private Boolean isConstrained0(Class<?> clazz) {
            Boolean constrained = CONSTRAINTS_ON_CLASSES.get(clazz);
            if (constrained == null) {
                constrained = detectConstraints(clazz);
                CONSTRAINTS_ON_CLASSES.put(clazz, constrained);
            }
            return constrained;
        }

        /**
         * INTERNAL:
         * Determines whether this class is subject to validation according to rules in BV spec.
         * Will accept upon first detected annotation - faster.
         * Uses reflection, recursion and DP.
         */
        private Boolean detectConstraints(Class<?> clazz) {
            if (detectAncestorConstraints(clazz)) return true;

            for (Field f : ReflectionUtils.getDeclaredFields(clazz)) {
                if ((f.getModifiers() & Modifier.STATIC) != 0) continue; // section 4.1 of BV spec
                if (detectFirstClassConstraints(f)) return true;
            }

            for (Method m : ReflectionUtils.getDeclaredMethods(clazz)) {
                if ((m.getModifiers() & Modifier.STATIC) != 0) continue; // section 4.1 of BV spec
                if (detectFirstClassConstraints(m) || detectParameterConstraints(m)) return true;
            }

            // length 0 if an interface, a primitive type, an array class, or void
            for (Constructor<?> c : ReflectionUtils.getDeclaredConstructors(clazz)) {
                if (clazz.isEnum()) continue; // cannot construct enum instances during runtime
                if (detectFirstClassConstraints(c) || detectParameterConstraints(c)) return true;
            }
            return false;
        }

        /**
         * Recursively detects constraints on ancestors. Uses strong form of dynamic programming.
         *
         * @param clazz class whose ancestors are to be scanned
         * @return true if any of the ancestors are constrained
         */
        private boolean detectAncestorConstraints(Class<?> clazz) {
            /* If this Class represents either the Object class, an interface, a primitive type, or void, then null is
               returned. If this object represents an array class then the Class object representing the Object class
               is returned. */
            Class<?> superClass = clazz.getSuperclass();
            if (superClass == null) return false;
            return memoizer.compute(cds, superClass);
        }

        private boolean detectFirstClassConstraints(AccessibleObject accessibleObject) {
            for (Annotation a : accessibleObject.getDeclaredAnnotations()) {
                final Class<? extends Annotation> annType = a.annotationType();
                if (KNOWN_CONSTRAINTS.contains(annType.getName())){
                    return true;
                }
                // detect custom annotations
                for (Annotation annOnAnnType : annType.getAnnotations()) {
                    final Class<? extends Annotation> annTypeOnAnnType = annOnAnnType.annotationType();
                    if (Constraint.class == annTypeOnAnnType) {
                        KNOWN_CONSTRAINTS.add(annType.getName());
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean detectParameterConstraints(Executable c) {
            for (Annotation[] aa : c.getParameterAnnotations())
                for (Annotation a : aa) {
                    final Class<? extends Annotation> annType = a.annotationType();
                    if (KNOWN_CONSTRAINTS.contains(annType.getName())) {
                        return true;
                    }
                    // detect custom annotations
                    for (Annotation annOnAnnType : annType.getAnnotations()) {
                        final Class<? extends Annotation> annTypeOnAnnType = annOnAnnType.annotationType();
                        if (Constraint.class == annTypeOnAnnType) {
                            KNOWN_CONSTRAINTS.add(annType.getName());
                            return true;
                        }
                    }
                }
            return false;
        }
    }

    /**
     * INTERNAL:
     * Reveals whether any of the class's {@link java.lang.reflect.AccessibleObject}s are constrained by Bean Validation
     * annotations or custom constraints.
     *
     * Will accept upon first detected annotation - faster.
     */
    private Boolean detectConstraints(Class<?> clazz) {
        for (Field f : getDeclaredFields(clazz)) {
            if (detectFirstClassConstraints(f)) return true;
        }
        for (Method m : getDeclaredMethods(clazz)) {
            if (detectFirstClassConstraints(m) || detectParameterConstraints(m)) return true;
        }
        for (Constructor<?> c : getDeclaredConstructors(clazz)) {
            if (detectFirstClassConstraints(c) || detectParameterConstraints(c)) return true;
        }
        return false;
    }

    private boolean detectFirstClassConstraints(AccessibleObject accessibleObject) {
        for (Annotation a : accessibleObject.getDeclaredAnnotations()) {
            final Class<? extends Annotation> annType = a.annotationType();
            final String annTypeCanonicalName = annType.getCanonicalName();

            if (KNOWN_CONSTRAINTS.contains(annTypeCanonicalName)) {
                return true;
            }
            // Check for custom annotations.
            for (Annotation annOnAnnType : annType.getAnnotations()) {
                final Class<? extends Annotation> annTypeOnAnnType = annOnAnnType.annotationType();
                if ("javax.validation.Constraint".equals(annTypeOnAnnType.getCanonicalName())) {
                    // Avoid adding anonymous class constraints.
                    if (annTypeCanonicalName != null) {
                        KNOWN_CONSTRAINTS.add(annTypeCanonicalName);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private boolean detectParameterConstraints(Executable c) {
        for (Annotation[] aa : c.getParameterAnnotations())
            for (Annotation a : aa) {
                final Class<? extends Annotation> annType = a.annotationType();
                final String annTypeCanonicalName = annType.getCanonicalName();

                if (KNOWN_CONSTRAINTS.contains(annTypeCanonicalName)) {
                    return true;
                }
                // Check for custom annotations.
                for (Annotation annOnAnnType : annType.getAnnotations()) {
                    final Class<? extends Annotation> annTypeOnAnnType = annOnAnnType.annotationType();
                    if ("javax.validation.Constraint".equals(annTypeOnAnnType.getCanonicalName())) {
                        // Avoid adding anonymous class constraints.
                        if (annTypeCanonicalName != null) {
                            KNOWN_CONSTRAINTS.add(annTypeCanonicalName);
                        }
                        return true;
                    }
                }
            }
        return false;
    }

    /**
     * Retrieves declared fields.
     * <p/>
     * If security is enabled, makes {@linkplain java.security.AccessController#doPrivileged(PrivilegedAction)
     * privileged calls}.
     *
     * @param clazz fields of that class will be returned
     * @return array of declared fields
     * @see Class#getDeclaredFields()
     */
    private Field[] getDeclaredFields(final Class<?> clazz) {
        return PrivilegedAccessHelper.shouldUsePrivilegedAccess()
                ? AccessController.doPrivileged(
                new PrivilegedAction<Field[]>() {
                    @Override
                    public Field[] run() {
                        return clazz.getDeclaredFields();
                    }
                })
                : PrivilegedAccessHelper.getDeclaredFields(clazz);
    }

    /**
     * Retrieves declared methods.
     * <p/>
     * If security is enabled, makes {@linkplain java.security.AccessController#doPrivileged(PrivilegedAction)
     * privileged calls}.
     *
     * @param clazz methods of that class will be returned
     * @return array of declared methods
     * @see Class#getDeclaredMethods()
     */
    private Method[] getDeclaredMethods(final Class<?> clazz) {
        return PrivilegedAccessHelper.shouldUsePrivilegedAccess()
                ? AccessController.doPrivileged(
                new PrivilegedAction<Method[]>() {
                    @Override
                    public Method[] run() {
                        return clazz.getDeclaredMethods();
                    }
                })
                : PrivilegedAccessHelper.getDeclaredMethods(clazz);
    }

    /**
     * Retrieves declared constructors.
     * <p/>
     * If security is enabled, makes {@linkplain java.security.AccessController#doPrivileged(PrivilegedAction)
     * privileged calls}.
     *
     * @param clazz constructors of that class will be returned
     * @return array of declared constructors
     * @see Class#getDeclaredConstructors()
     */
    private Constructor[] getDeclaredConstructors(final Class<?> clazz) {
        return PrivilegedAccessHelper.shouldUsePrivilegedAccess()
                ? AccessController.doPrivileged(
                new PrivilegedAction<Constructor[]>() {
                    @Override
                    public Constructor[] run() {
                        return clazz.getDeclaredConstructors();
                    }
                })
                : clazz.getDeclaredConstructors();
    }

    /**
     * Adds canonical names of bean validation constraints into set of known constraints (internally represented by
     * map).
     * Canonical name is the name that would be used in an import statement and uniquely identifies the class,
     * i.e. anonymous classes receive a 'null' value as their canonical name,
     * which allows no ambiguity and is what we are looking for.
     */
    private static void initializeKnownConstraints() {
        KNOWN_CONSTRAINTS.add("javax.validation.Valid");
        KNOWN_CONSTRAINTS.add("javax.validation.constraints.Max");
        KNOWN_CONSTRAINTS.add("javax.validation.constraints.Min");
        KNOWN_CONSTRAINTS.add("javax.validation.constraints.DecimalMax");
        KNOWN_CONSTRAINTS.add("javax.validation.constraints.DecimalMin");
        KNOWN_CONSTRAINTS.add("javax.validation.constraints.Digits");
        KNOWN_CONSTRAINTS.add("javax.validation.constraints.NotNull");
        KNOWN_CONSTRAINTS.add("javax.validation.constraints.Pattern");
        KNOWN_CONSTRAINTS.add("javax.validation.constraints.Size");
        KNOWN_CONSTRAINTS.add("javax.validation.constraints.AssertTrue");
        KNOWN_CONSTRAINTS.add("javax.validation.constraints.AssertFalse");
        KNOWN_CONSTRAINTS.add("javax.validation.constraints.Future");
        KNOWN_CONSTRAINTS.add("javax.validation.constraints.Past");
        KNOWN_CONSTRAINTS.add("javax.validation.constraints.List.Max");
        KNOWN_CONSTRAINTS.add("javax.validation.constraints.List.Min");
        KNOWN_CONSTRAINTS.add("javax.validation.constraints.List.DecimalMax");
        KNOWN_CONSTRAINTS.add("javax.validation.constraints.List.DecimalMin");
        KNOWN_CONSTRAINTS.add("javax.validation.constraints.List.Digits");
        KNOWN_CONSTRAINTS.add("javax.validation.constraints.List.NotNull");
        KNOWN_CONSTRAINTS.add("javax.validation.constraints.List.Pattern");
        KNOWN_CONSTRAINTS.add("javax.validation.constraints.List.Size");
        KNOWN_CONSTRAINTS.add("javax.validation.constraints.List.AssertTrue");
        KNOWN_CONSTRAINTS.add("javax.validation.constraints.List.AssertFalse");
        KNOWN_CONSTRAINTS.add("javax.validation.constraints.List.Future");
        KNOWN_CONSTRAINTS.add("javax.validation.constraints.List.Past");
    }


    /**
     * Calculate the next power of 2, greater than or equal to x.<p>
     * From Hacker's Delight, Chapter 3, Harry S. Warren Jr.
     *
     * @param x integer greater than 0
     * @return next power of two
     */
    private static int nextPowerOfTwo(int x) {
        assert x > 0;
        x |= --x >> 1;
        x |= x >> 2;
        x |= x >> 4;
        x |= x >> 8;
        x |= x >> 16;
        return x + 1;
    }
}
