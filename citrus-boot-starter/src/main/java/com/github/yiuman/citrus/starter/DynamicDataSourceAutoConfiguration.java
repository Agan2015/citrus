package com.github.yiuman.citrus.starter;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.xa.DruidXADataSource;
import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusProperties;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusPropertiesCustomizer;
import com.baomidou.mybatisplus.autoconfigure.SpringBootVFS;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.incrementer.IKeyGenerator;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.core.injector.ISqlInjector;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.github.yiuman.citrus.support.datasource.*;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.TypeHandler;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jta.atomikos.AtomikosDataSourceBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.*;
import java.util.function.Consumer;

/**
 * 动态数据源自动配置
 *
 * @author yiuman
 * @date 2020/12/1
 */
@SuppressWarnings("rawtypes")
@Configuration
@EnableConfigurationProperties({DynamicDataSourceProperties.class, DataSourceProperties.class, MybatisPlusProperties.class})
@ConditionalOnClass({SqlSessionFactory.class, SqlSessionFactoryBean.class})
public class DynamicDataSourceAutoConfiguration implements InitializingBean {

    private final DataSourceProperties dataSourceProperties;

    private final DynamicDataSourceProperties dynamicDataSourceProperties;

    private final MybatisPlusProperties mybatisPlusProperties;

    private final org.apache.ibatis.plugin.Interceptor[] interceptors;

    private final TypeHandler[] typeHandlers;

    private final LanguageDriver[] languageDrivers;

    private final ResourceLoader resourceLoader;

    private final DatabaseIdProvider databaseIdProvider;

    private final List<ConfigurationCustomizer> configurationCustomizers;

    private final List<MybatisPlusPropertiesCustomizer> mybatisPlusPropertiesCustomizers;

    private final ApplicationContext applicationContext;

    public DynamicDataSourceAutoConfiguration(DataSourceProperties dataSourceProperties,
                                              DynamicDataSourceProperties dynamicDataSourceProperties,
                                              MybatisPlusProperties mybatisPlusProperties,
                                              ObjectProvider<Interceptor[]> interceptorsProvider,
                                              ObjectProvider<TypeHandler[]> typeHandlersProvider,
                                              ObjectProvider<LanguageDriver[]> languageDriversProvider,
                                              ResourceLoader resourceLoader,
                                              ObjectProvider<DatabaseIdProvider> databaseIdProvider,
                                              ObjectProvider<List<ConfigurationCustomizer>> configurationCustomizersProvider,
                                              ObjectProvider<List<MybatisPlusPropertiesCustomizer>> mybatisPlusPropertiesCustomizerProvider,
                                              ApplicationContext applicationContext) {
        this.dataSourceProperties = dataSourceProperties;
        this.dynamicDataSourceProperties = dynamicDataSourceProperties;
        this.mybatisPlusProperties = mybatisPlusProperties;
        this.interceptors = interceptorsProvider.getIfAvailable();
        this.typeHandlers = typeHandlersProvider.getIfAvailable();
        this.languageDrivers = languageDriversProvider.getIfAvailable();
        this.resourceLoader = resourceLoader;
        this.databaseIdProvider = databaseIdProvider.getIfAvailable();
        this.configurationCustomizers = configurationCustomizersProvider.getIfAvailable();
        this.mybatisPlusPropertiesCustomizers = mybatisPlusPropertiesCustomizerProvider.getIfAvailable();
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterPropertiesSet() {
        if (!CollectionUtils.isEmpty(mybatisPlusPropertiesCustomizers)) {
            mybatisPlusPropertiesCustomizers.forEach(i -> i.customize(mybatisPlusProperties));
        }
        checkConfigFileExists();
    }

    private void checkConfigFileExists() {
        if (this.mybatisPlusProperties.isCheckConfigLocation() && StringUtils.hasText(this.mybatisPlusProperties.getConfigLocation())) {
            Resource resource = this.resourceLoader.getResource(this.mybatisPlusProperties.getConfigLocation());
            Assert.state(resource.exists(),
                    "Cannot find config location: " + resource + " (please add config file or check your Mybatis configuration)");
        }
    }

    /**
     * 配置动态数据源
     *
     * @return DataSource
     */
    @Bean
    @ConditionalOnMissingBean
    public DataSource dynamicDatasource() {
        DynamicDataSource dynamicDataSource = new DynamicDataSource();
        Map<String, DataSourceProperties> dataSourcePropertiesMap = dynamicDataSourceProperties.getDatasource();
        int dataSourceSize = Objects.nonNull(dataSourcePropertiesMap) ? dataSourcePropertiesMap.size() : 0;
        Map<Object, Object> dataSourceMap = new HashMap<>(dataSourceSize + 1);
        DataSource defaultDataSource;
        String primary = Optional.of(dynamicDataSourceProperties.getPrimary()).orElse("default");
        //是否开启多数据源事务
        boolean enableMultipleTx = dynamicDataSourceProperties.isEnableMultipleTx();
        if (dataSourceSize > 0) {
            defaultDataSource = buildDataSource(primary, dataSourceProperties, enableMultipleTx);
            dataSourcePropertiesMap.forEach((key, properties) -> dataSourceMap.put(key, buildDataSource(key, properties, enableMultipleTx)));
        } else {
            defaultDataSource = buildDataSource(null, dataSourceProperties, false);
        }
        dataSourceMap.put(primary, defaultDataSource);
        dynamicDataSource.setTargetDataSources(dataSourceMap);
        dynamicDataSource.setDefaultTargetDataSource(defaultDataSource);
        return dynamicDataSource;
    }

    @Bean
    @ConditionalOnMissingBean(SqlSessionTemplate.class)
    public DynamicSqlSessionTemplate sqlSessionTemplate() throws Exception {
        Map<String, DataSourceProperties> dataSourcePropertiesMap = dynamicDataSourceProperties.getDatasource();
        int dataSourceSize = Objects.nonNull(dataSourcePropertiesMap) ? dataSourcePropertiesMap.size() : 0;
        Map<Object, SqlSessionFactory> sqlSessionFactoryMap = new HashMap<>(dataSourceSize + 1);

        //是否开启多数据源事务
        boolean enableMultipleTx = dynamicDataSourceProperties.isEnableMultipleTx();
        DataSource defaultDataSource;
        String primary = Optional.of(dynamicDataSourceProperties.getPrimary()).orElse("");
        if (dataSourceSize > 0) {
            defaultDataSource = buildDataSource(primary, dataSourceProperties, enableMultipleTx);
            for (Map.Entry<String, DataSourceProperties> entry : dataSourcePropertiesMap.entrySet()) {
                sqlSessionFactoryMap.put(entry.getKey(), createSqlSessionFactory(buildDataSource(entry.getKey(), entry.getValue(), enableMultipleTx)));
            }
        } else {
            defaultDataSource = buildDataSource(null, dataSourceProperties, false);
        }
        SqlSessionFactory defaultSqlSessionFactory = createSqlSessionFactory(defaultDataSource);
        sqlSessionFactoryMap.put(primary, defaultSqlSessionFactory);
        DynamicSqlSessionTemplate dynamicSqlSessionTemplate = new DynamicSqlSessionTemplate(defaultSqlSessionFactory);
        dynamicSqlSessionTemplate.setTargetSqlSessionFactories(sqlSessionFactoryMap);
        dynamicSqlSessionTemplate.setDefaultTargetSqlSessionFactory(defaultSqlSessionFactory);
        dynamicSqlSessionTemplate.setStrict(dynamicDataSourceProperties.isStrict());
        return dynamicSqlSessionTemplate;
    }

    @Bean
    public DynamicDataSourceAnnotationAdvisor dynamicDataSourceAnnotationAdvisor() {
        return new DynamicDataSourceAnnotationAdvisor(new DynamicDataSourceAnnotationInterceptor());
    }

    private DataSource buildDataSource(String resourceName, DataSourceProperties properties, boolean enableMultipleTx) {
        return enableMultipleTx ? buildDruidXADataSource(resourceName, properties) : buildDruidDataSource(properties);
    }


    /**
     * 根据配置构建的druid数据源
     *
     * @param properties 数据源配置
     * @return DruidDataSource
     */
    public DataSource buildDruidDataSource(DataSourceProperties properties) {
        DruidDataSource druidDataSource = new DruidDataSource();
        druidDataSource.setUrl(properties.getUrl());
        druidDataSource.setUsername(properties.getUsername());
        druidDataSource.setPassword(properties.getPassword());
        druidDataSource.setDriverClassName(properties.getDriverClassName());
        return druidDataSource;
    }


    /**
     * 根据配置构建XA数据源
     *
     * @param resourceName 资源名，用于定义XA唯一资源
     * @param properties   数据源配置
     * @return XA数据源
     */
    public DataSource buildDruidXADataSource(String resourceName, DataSourceProperties properties) {
        DruidXADataSource druidDataSource = new DruidXADataSource();
        druidDataSource.setUrl(properties.getUrl());
        druidDataSource.setUsername(properties.getUsername());
        druidDataSource.setPassword(properties.getPassword());
        druidDataSource.setDriverClassName(properties.getDriverClassName());

        AtomikosDataSourceBean atomikosDataSourceBean = new AtomikosDataSourceBean();
        atomikosDataSourceBean.setXaDataSource(druidDataSource);
        atomikosDataSourceBean.setUniqueResourceName(String.format("%s$$%s", resourceName, UUID.randomUUID().toString().substring(0, 15)));
        return atomikosDataSourceBean;
    }

    /**
     * 替换掉mybatis-plus中的自动配置
     *
     * @param dynamicSqlSessionTemplate 动态数据源模板
     * @return SqlSessionFactory
     * @throws Exception in case of creation errors
     */
    @Bean
    @ConditionalOnMissingBean
    public SqlSessionFactory sqlSessionFactory(DynamicSqlSessionTemplate dynamicSqlSessionTemplate) throws Exception {
        return dynamicSqlSessionTemplate.getSqlSessionFactory();
    }

    /**
     * 用于动态数据源
     * 根据数据源创建SqlSessionFactory
     *
     * @param dataSource 数据源
     * @return SqlSessionFactory
     * @throws Exception in case of creation errors
     */
    private SqlSessionFactory createSqlSessionFactory(DataSource dataSource) throws Exception {
        // 使用 MybatisSqlSessionFactoryBean 而不是 SqlSessionFactoryBean
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setVfs(SpringBootVFS.class);
        if (StringUtils.hasText(this.mybatisPlusProperties.getConfigLocation())) {
            factory.setConfigLocation(this.resourceLoader.getResource(this.mybatisPlusProperties.getConfigLocation()));
        }
        applyConfiguration(factory);
        if (this.mybatisPlusProperties.getConfigurationProperties() != null) {
            factory.setConfigurationProperties(this.mybatisPlusProperties.getConfigurationProperties());
        }

        if (this.databaseIdProvider != null) {
            factory.setDatabaseIdProvider(this.databaseIdProvider);
        }
        if (StringUtils.hasLength(this.mybatisPlusProperties.getTypeAliasesPackage())) {
            factory.setTypeAliasesPackage(this.mybatisPlusProperties.getTypeAliasesPackage());
        }
        if (this.mybatisPlusProperties.getTypeAliasesSuperType() != null) {
            factory.setTypeAliasesSuperType(this.mybatisPlusProperties.getTypeAliasesSuperType());
        }
        if (StringUtils.hasLength(this.mybatisPlusProperties.getTypeHandlersPackage())) {
            factory.setTypeHandlersPackage(this.mybatisPlusProperties.getTypeHandlersPackage());
        }
        if (!ObjectUtils.isEmpty(this.typeHandlers)) {
            factory.setTypeHandlers(this.typeHandlers);
        }
        if (!ObjectUtils.isEmpty(this.mybatisPlusProperties.resolveMapperLocations())) {
            factory.setMapperLocations(this.mybatisPlusProperties.resolveMapperLocations());
        }

        Class<? extends LanguageDriver> defaultLanguageDriver = this.mybatisPlusProperties.getDefaultScriptingLanguageDriver();
        if (!ObjectUtils.isEmpty(this.languageDrivers)) {
            factory.setScriptingLanguageDrivers(this.languageDrivers);
        }
        Optional.ofNullable(defaultLanguageDriver).ifPresent(factory::setDefaultScriptingLanguageDriver);
        if (!ObjectUtils.isEmpty(this.interceptors)) {
            factory.setPlugins(this.interceptors);
        }
        //自定义枚举包
        if (StringUtils.hasLength(this.mybatisPlusProperties.getTypeEnumsPackage())) {
            factory.setTypeEnumsPackage(this.mybatisPlusProperties.getTypeEnumsPackage());
        }
        // 这里每个MybatisSqlSessionFactoryBean对应的都是独立的一个GlobalConfig，不然会出现问题
        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        //去除打印
        globalConfig.setBanner(false);
        //  注入填充器
        this.getBeanThen(MetaObjectHandler.class, globalConfig::setMetaObjectHandler);
        // 注入主键生成器
        this.getBeanThen(IKeyGenerator.class, i -> globalConfig.getDbConfig().setKeyGenerator(i));
        // 注入sql注入器
        this.getBeanThen(ISqlInjector.class, globalConfig::setSqlInjector);
        // 注入ID生成器
        this.getBeanThen(IdentifierGenerator.class, globalConfig::setIdentifierGenerator);
        //设置 GlobalConfig 到 MybatisSqlSessionFactoryBean
        factory.setGlobalConfig(globalConfig);
        return factory.getObject();
    }

    /**
     * 入参使用 MybatisSqlSessionFactoryBean
     *
     * @param factory MybatisSqlSessionFactoryBean
     */
    private void applyConfiguration(MybatisSqlSessionFactoryBean factory) {
        // 使用 MybatisConfiguration
        factory.setConfiguration(getCopyMybatisConfiguration(this.mybatisPlusProperties));
    }

    private MybatisConfiguration getCopyMybatisConfiguration(MybatisPlusProperties mybatisPlusProperties) {
        MybatisConfiguration mybatisConfiguration = new MybatisConfiguration();
        BeanUtils.copyProperties(mybatisPlusProperties.getConfiguration(), mybatisConfiguration);
        if (!CollectionUtils.isEmpty(this.configurationCustomizers)) {
            for (ConfigurationCustomizer customizer : this.configurationCustomizers) {
                customizer.customize(mybatisConfiguration);
            }
        }

        return mybatisConfiguration;
    }

    /**
     * 检查spring容器里是否有对应的bean,有则进行消费
     *
     * @param clazz    class
     * @param consumer 消费
     * @param <T>      泛型
     */
    private <T> void getBeanThen(Class<T> clazz, Consumer<T> consumer) {
        if (this.applicationContext.getBeanNamesForType(clazz, false, false).length > 0) {
            consumer.accept(this.applicationContext.getBean(clazz));
        }
    }


}
