package com.time.tdd.di.container;

/**
 * @author XuJian
 * @date 2023-03-06 21:26
 **/
interface ScopeProvider {
    ComponentProvider<?> create(ComponentProvider<?> provider);
}

