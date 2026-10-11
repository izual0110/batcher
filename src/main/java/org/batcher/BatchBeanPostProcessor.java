package org.batcher;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;

/** Finds batch methods, validates their contract and creates Spring AOP proxies. */
public class BatchBeanPostProcessor implements BeanPostProcessor, DisposableBean, Ordered, SmartLifecycle {

    private final BatchExecutor executor = new BatchExecutor();
    private volatile boolean running;
    private volatile boolean stopped;

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        Map<Method, Batch> annotations = MethodIntrospector.selectMethods(targetClass,
                (MethodIntrospector.MetadataLookup<Batch>) method ->
                        AnnotatedElementUtils.findMergedAnnotation(method, Batch.class));
        if (annotations.isEmpty()) {
            return bean;
        }
        if (!AopUtils.isJdkDynamicProxy(bean) && Modifier.isFinal(targetClass.getModifiers())) {
            throw new BeanCreationException(beanName, "@Batch requires a non-final class: " + targetClass.getName());
        }

        Map<Method, BatchMethod> methods = new HashMap<>();
        annotations.forEach((method, settings) -> {
            validate(beanName, method, settings);
            Method invocable;
            try {
                invocable = AopUtils.selectInvocableMethod(method, bean.getClass());
                ReflectionUtils.makeAccessible(invocable);
            } catch (IllegalStateException exception) {
                throw new BeanCreationException(beanName, "@Batch method is not exposed by the existing proxy: " + method,
                        exception);
            }
            // A unique key per bean instance and method also isolates prototype beans.
            methods.put(canonical(method, targetClass), new BatchMethod(new Object(), settings, invocable));
        });

        ProxyFactory factory = new ProxyFactory(bean);
        factory.setProxyTargetClass(!AopUtils.isJdkDynamicProxy(bean));
        factory.addAdvice((MethodInterceptor) invocation -> {
            BatchMethod batchMethod = methods.get(canonical(invocation.getMethod(), targetClass));
            if (batchMethod == null) {
                return invocation.proceed();
            }
            String description = beanName + "." + batchMethod.method().getName();
            executor.enqueue(batchMethod.key(), batchMethod.settings(), description,
                    elements -> invoke(bean, batchMethod.method(), elements),
                    (Collection<?>) invocation.getArguments()[0]);
            return null;
        });
        try {
            return factory.getProxy(bean.getClass().getClassLoader());
        } catch (RuntimeException exception) {
            throw new BeanCreationException(beanName, "Cannot create @Batch proxy for " + targetClass.getName(), exception);
        }
    }

    private static Method canonical(Method method, Class<?> targetClass) {
        return BridgeMethodResolver.findBridgedMethod(AopUtils.getMostSpecificMethod(method, targetClass));
    }

    private static void validate(String beanName, Method method, Batch settings) {
        int modifiers = method.getModifiers();
        if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)
                || method.getReturnType() != void.class || method.getParameterCount() != 1
                || (method.getParameterTypes()[0] != Collection.class && method.getParameterTypes()[0] != List.class)) {
            throw new BeanCreationException(beanName,
                    "@Batch requires a public, non-static, non-final void method with one Collection or List argument: "
                            + method);
        }
        if (settings.timeout() <= 0 || settings.maxSize() <= 0) {
            throw new BeanCreationException(beanName, "@Batch timeout and maxSize must be positive: " + method);
        }
    }

    private static void invoke(Object bean, Method method, List<Object> elements) throws Throwable {
        try {
            method.invoke(bean, elements);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    @Override
    public void start() {
        if (stopped) {
            throw new IllegalStateException("A stopped batch processor cannot be restarted");
        }
        running = true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // Drain while target beans and their downstream resources are still alive.
        return Integer.MAX_VALUE;
    }

    @Override
    public void stop() {
        stopped = true;
        running = false;
        executor.close();
    }

    @Override
    public void destroy() {
        stop();
    }

    private record BatchMethod(Object key, Batch settings, Method method) {
    }
}
