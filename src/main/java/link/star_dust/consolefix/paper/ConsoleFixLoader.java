package link.star_dust.consolefix.paper;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

public final class ConsoleFixLoader implements PluginLoader {
    @Override
    public void classloader(PluginClasspathBuilder builder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        resolver.addDependency(new Dependency(new DefaultArtifact("org.spongepowered:configurate-yaml:4.2.0"), null));
        resolver.addRepository(new RemoteRepository.Builder("central", "default",
            MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());
        builder.addLibrary(resolver);
    }
}
