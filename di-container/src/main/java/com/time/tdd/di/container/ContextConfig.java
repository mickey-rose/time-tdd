package com.time.tdd.di.container;

import com.time.tdd.di.container.exceptions.CyclicDependenciesFoundException;
import com.time.tdd.di.container.exceptions.DependencyNotFoundException;
import com.time.tdd.di.container.exceptions.IllegalComponentException;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Stack;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jakarta.inject.Provider;
import jakarta.inject.Qualifier;
import jakarta.inject.Scope;
import jakarta.inject.Singleton;

/**
 * @author XuJian
 * @date 2023-02-24 21:49
 **/
public class ContextConfig {

    private final Map<Component, ComponentProvider<?>> components = new HashMap<>();
    private final Map<Class<?>, ScopeProvider> scopes = new HashMap<>();

    public ContextConfig() {
        scope(Singleton.class, SingletonProvider::new);
    }

    private static <Type> Optional<Annotation> scopeFrom(Class<Type> implementation) {
        return Arrays.stream(implementation.getAnnotations()).filter(a -> a.annotationType().isAnnotationPresent(Scope.class)).findFirst();
    }

    public <Type> void bind(Class<Type> type, Type instance) {
        components.put(new Component(type, null), (ComponentProvider<Type>) context -> instance);
    }

    public <Type> void bind(Class<Type> type, Type instance, Annotation... qualifiers) {
        if (Arrays.stream(qualifiers).anyMatch(q -> !q.annotationType().isAnnotationPresent(Qualifier.class))) {
            throw new IllegalComponentException();
        }
        for (Annotation qualifier : qualifiers) {
            components.put(new Component(type, qualifier), context -> instance);
        }
    }

    public <Type, Implementation extends Type> void bind(Class<Type> type, Class<Implementation> implementation) {
        bind(type, implementation, implementation.getAnnotations());
    }

    public <Type, Implementation extends Type> void bind(Class<Type> type, Class<Implementation> implementation,
                                                         Annotation... annotations) {

        Map<Class<?>, List<Annotation>> annotationGroups =
            Arrays.stream(annotations).collect(Collectors.groupingBy(this::typeOf, Collectors.toList()));

        if (annotationGroups.containsKey(illegal.class)) {
            throw new IllegalComponentException();
        }

        bind(type, annotationGroups.getOrDefault(Qualifier.class, List.of()),
            createScopedProvider(implementation, annotationGroups.getOrDefault(Scope.class, List.of())));
    }

    private <Type> ComponentProvider<?> createScopedProvider(Class<Type> implementation, List<Annotation> scopes) {
        if (scopes.size() > 1) {
            throw new IllegalComponentException();
        }
        ComponentProvider<?> injectionProvider = new InjectionProvider<>(implementation);

        return scopes.stream().findFirst().or(() -> scopeFrom(implementation))
            .<ComponentProvider<?>>map(s -> getScopeProvider(s, injectionProvider)).orElse(injectionProvider);
    }

    private <Type> void bind(Class<Type> type, List<Annotation> qualifiers, ComponentProvider<?> provider) {
        if (qualifiers.isEmpty()) {
            components.put(new Component(type, null), provider);
        }
        for (Annotation qualifier : qualifiers) {
            components.put(new Component(type, qualifier), provider);
        }
    }

    private Class<?> typeOf(Annotation annotation) {
        Class<? extends Annotation> type = annotation.annotationType();
        return Stream.of(Qualifier.class, Scope.class).filter(type::isAnnotationPresent).findFirst().orElse(illegal.class);
    }

    private ComponentProvider<?> getScopeProvider(Annotation scope, ComponentProvider<?> provider) {
        if (!scopes.containsKey(scope.annotationType())) {
            throw new IllegalComponentException();
        }
        return scopes.get(scope.annotationType()).create(provider);
    }

    public Context getContext() {
        // check dependencies
        components.keySet().forEach(component -> checkDependencies(component, new Stack<>()));

        return new Context() {
            // Type -> Reference

            @Override
            public <ComponentType> Optional<ComponentType> get(ComponentRef<ComponentType> ref) {
                if (ref.isContainer()) {
                    if (ref.getContainer() != Provider.class) {
                        return Optional.empty();
                    }
                    return (Optional<ComponentType>) Optional.ofNullable(getProvider(ref))
                        .map(provider -> (Provider<Object>) () -> provider.get(this));
                }
                return Optional.ofNullable(getProvider(ref)).map(provider -> (ComponentType) provider.get(this));
            }
        };
    }

    private <ComponentType> ComponentProvider<?> getProvider(ComponentRef<ComponentType> ref) {
        return components.get(ref.component());
    }

    private void checkDependencies(Component component, Stack<Component> visiting) {
        for (ComponentRef dependency : components.get(component).getDependencies()) {
            if (!components.containsKey(dependency.component())) {
                throw new DependencyNotFoundException(component, dependency.component());
            }
            if (!dependency.isContainer()) {
                if (visiting.contains(dependency.component())) {
                    throw new CyclicDependenciesFoundException(visiting);
                }
                visiting.push(dependency.component());
                checkDependencies(dependency.component(), visiting);
                visiting.pop();
            }
        }
    }

    public <ScopeType extends Annotation> void scope(Class<ScopeType> scope, ScopeProvider provider) {
        scopes.put(scope, provider);
    }

    @interface illegal {
    }

}

