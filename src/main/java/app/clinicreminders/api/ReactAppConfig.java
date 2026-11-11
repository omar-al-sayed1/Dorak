package app.clinicreminders.api;

import java.io.IOException;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the React dashboard, built into {@code static/app/} by the frontend build, at {@code /app/}.
 * Its files are served as they are; any other address under {@code /app/} gets its
 * {@code index.html}, so a reloaded or shared page (/app/week) opens there and React shows it.
 */
@Configuration(proxyBeanMethods = false)
class ReactAppConfig implements WebMvcConfigurer {

    private static final String LOCATION = "classpath:/static/app/";
    private static final Resource INDEX = new ClassPathResource("static/app/index.html");

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/app/**")
                .addResourceLocations(LOCATION)
                // Built files have a hash in their names; index.html must always be asked for again.
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource location) throws IOException {
                        // The usual checks (readable, inside static/app/) first; then the app's page.
                        Resource file = super.getResource(path, location);
                        return file != null ? file : INDEX.exists() ? INDEX : null;
                    }
                });
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/app", "/app/");
        // The file handler turns an empty path away before any resolver sees it.
        registry.addViewController("/app/").setViewName("forward:/app/index.html");
    }
}
