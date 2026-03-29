/*
 * The MIT License
 *
 * Copyright 2016 Peter Hayes.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package jenkins.plugins.jobcacher;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.*;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.LocalChannel;
import hudson.util.ListBoxModel;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.Deflater;
import jenkins.plugins.itemstorage.GlobalItemStorage;
import jenkins.plugins.itemstorage.ObjectPath;
import jenkins.plugins.jobcacher.arbitrary.*;
import jenkins.plugins.jobcacher.arbitrary.WorkspaceHelper.TempFile;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.*;

/**
 * This class implements a Cache where the user can configure a path on the executor that will be cached.  Users can
 * reference environment variables on the executor in the path and supply an includes and excludes pattern to limit the
 * files that are cached.
 *
 * @author Peter Hayes
 */
public class KeyFileCache extends Cache {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String CACHE_VALIDITY_DECIDING_FILE_HASH_FILE_EXTENSION = ".hash";
    private static final String CACHE_FILENAME_PART_SEP = "-";

    private String path;
    private String includes;
    private String excludes;
    private boolean useDefaultExcludes = true;
    private String key;
    private String restoreKey;
    private ArbitraryFileCache.CompressionMethod compressionMethod = ArbitraryFileCache.CompressionMethod.TARGZ;
    private String cacheName;

    @DataBoundConstructor
    public KeyFileCache(String path, String includes, String excludes) {
        this.path = path;
        this.includes = StringUtils.isNotBlank(includes) ? includes : "**/*";
        this.excludes = excludes;
    }

    @DataBoundSetter
    public void setUseDefaultExcludes(boolean useDefaultExcludes) {
        this.useDefaultExcludes = useDefaultExcludes;
    }

    public boolean getUseDefaultExcludes() {
        return useDefaultExcludes;
    }

    @DataBoundSetter
    public void setCompressionMethod(ArbitraryFileCache.CompressionMethod compressionMethod) {
        if (compressionMethod == ArbitraryFileCache.CompressionMethod.NONE) {
            compressionMethod = ArbitraryFileCache.CompressionMethod.TARGZ;
        }

        this.compressionMethod = compressionMethod;
    }

    public ArbitraryFileCache.CompressionMethod getCompressionMethod() {
        return compressionMethod;
    }

    @DataBoundSetter
    public void setKey(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getPath() {
        return path;
    }

    public void setIncludes(String includes) {
        this.includes = includes;
    }

    public String getIncludes() {
        return includes;
    }

    public void setExcludes(String excludes) {
        this.excludes = excludes;
    }

    public String getExcludes() {
        return excludes;
    }

    public String getCacheName() {
        return cacheName;
    }

    @DataBoundSetter
    public void setCacheName(String cacheName) {
        this.cacheName = cacheName;
    }

    private String getSkipCacheTriggerFileHashFileName() {
        return createCacheBaseName() + CACHE_VALIDITY_DECIDING_FILE_HASH_FILE_EXTENSION;
    }

    public String createCacheBaseName() {
        String generatedCacheName = deriveCachePath(path);
        if (StringUtils.isEmpty(this.cacheName)) {
            return generatedCacheName;
        }

        return generatedCacheName + CACHE_FILENAME_PART_SEP + this.cacheName;
    }

    @Override
    public String getTitle() {
        return jenkins.plugins.jobcacher.Messages.KeyFileCache_displayName();
    }

    @Override
    public Saver cache(
            ObjectPath cachesRoot,
            ObjectPath fallbackCachesRoot,
            Run<?, ?> build,
            FilePath workspace,
            Launcher launcher,
            TaskListener listener,
            EnvVars initialEnvironment,
            boolean skipRestore)
            throws IOException, InterruptedException {
        String expandedPath = initialEnvironment.expand(path);
        FilePath resolvedPath = workspace.child(expandedPath);

        ExistingCache existingCache = resolveExistingValidCache(cachesRoot, fallbackCachesRoot, workspace, listener);
        if (existingCache == null) {
            logMessage("Skip restoring cache as no up-to-date cache exists", listener);
            return new SaverImpl(expandedPath);
        }

        if (skipRestore) {
            logMessage("Skip restoring cache due skipRestore parameter", listener);
        } else {
            logMessage("Restoring cache...", listener);
            long cacheRestorationStartTime = System.nanoTime();

            try {
                existingCache.restore(resolvedPath, workspace);

                long cacheRestorationEndTime = System.nanoTime();
                logMessage(
                        "Cache restored in "
                                + Duration.ofNanos(cacheRestorationEndTime - cacheRestorationStartTime)
                                .toMillis() + "ms",
                        listener);
            } catch (Exception e) {
                logMessage("Failed to restore cache, cleaning up " + path + "...", e, listener);
                resolvedPath.deleteRecursive();
            }
        }

        return new SaverImpl(expandedPath);
    }

    private boolean isCacheKeyConfigured(){
        return StringUtils.isNotEmpty(key);
    }

    private ExistingCache resolveExistingValidCache(
            ObjectPath cachesRoot, ObjectPath fallbackCachesRoot, FilePath workspace, TaskListener listener)
            throws IOException, InterruptedException {
        logMessage("Searching cache in job specific caches...", listener);
        ExistingCache cache = resolveExistingValidCache(cachesRoot, workspace, listener);
        if (cache != null) {
            logMessage("Found cache in job specific caches", listener);
            return cache;
        }

        logMessage("Searching cache in default caches...", listener);
        cache = resolveExistingValidCache(fallbackCachesRoot, workspace, listener);
        if (cache != null) {
            logMessage("Found cache in default caches", listener);
            return cache;
        }

        return null;
    }

    private ExistingCache resolveExistingValidCache(ObjectPath cachesRoot, FilePath workspace, TaskListener listener)
            throws IOException, InterruptedException {
        ExistingCache existingCache = resolveExistingCache(cachesRoot);
        if (existingCache == null || !existingCache.getCompressionMethod().isSupported()) {
            return null;
        }

        if (!isCacheKeyConfigured()) {
            return existingCache;
        }

        return isCacheOutdated(cachesRoot, workspace, listener) ? null : existingCache;
    }

    private ObjectPath resolveKeyFilePath(ObjectPath cachesRoot) throws IOException, InterruptedException {
        return cachesRoot.child(createCacheBaseName() + "_key.txt");
    }

    private ExistingCache resolveExistingCache(ObjectPath cachesRoot) throws IOException, InterruptedException {
        if (cachesRoot == null) {
            return null;
        }

        for (ArbitraryFileCache.CompressionMethod compressionMethod : ArbitraryFileCache.CompressionMethod.values()) {
            ObjectPath cache = resolveCachePathForCompressionMethod(cachesRoot, compressionMethod);
            if (cache.exists()) {
                return new ExistingCache(cache, compressionMethod);
            }
        }

        return null;
    }

    private ObjectPath resolveCachePathForCompressionMethod(ObjectPath cachesRoot, ArbitraryFileCache.CompressionMethod compressionMethod)
            throws IOException, InterruptedException {
        return cachesRoot.child(compressionMethod.getCacheStrategy().createCacheName(createCacheBaseName()));
    }

    private boolean keyFileMatchesRestoreKey(
            ObjectPath keyFile, FilePath workspace, TaskListener listener)
            throws IOException, InterruptedException {
        if(!keyFile.exists()){
            return false;
        }

        try (TempFile tempFile =
                     WorkspaceHelper.createTempFile(workspace, CACHE_VALIDITY_DECIDING_FILE_HASH_FILE_EXTENSION)) {
            keyFile.copyTo(tempFile.get());

            try (InputStream inputStream = tempFile.get().read()) {
                String previousKey = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
                if(restoreKey.length() > previousKey.length()){
                    return false;
                }
                for(int i = 0; i < restoreKey.length(); i++){
                    if(previousKey.charAt(i) != restoreKey.charAt(i)){
                        return false;
                    }
                }

                return true;
            }
        }
    }

    private boolean isCacheOutdated(ObjectPath cachesRoot, FilePath workspace, TaskListener listener)
            throws IOException, InterruptedException {
        ObjectPath previousClearCacheTriggerFileHash = resolveKeyFilePath(cachesRoot);
        if (!previousClearCacheTriggerFileHash.exists()) {
            logMessage(
                    "cacheValidityDecidingFile configured, but previous hash not available - cache outdated", listener);
            return true;
        }

        if (!keyFileMatchesRestoreKey(previousClearCacheTriggerFileHash, workspace, listener)) {
            logMessage(
                    "cacheValidityDecidingFile configured, but previous hash does not match - cache outdated",
                    listener);
            return true;
        }

        return false;
    }

    private class SaverImpl extends Saver {

        @Serial
        private static final long serialVersionUID = 1L;

        private final String expandedPath;

        public SaverImpl(String expandedPath) {
            this.expandedPath = expandedPath;
        }

        @Override
        public long calculateSize(
                ObjectPath objectPath, Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener)
                throws IOException, InterruptedException {
            return workspace.child(expandedPath).act(new DirectorySize(includes, excludes));
        }

        @Override
        public void save(
                ObjectPath cachesRoot,
                ObjectPath defaultCachesRoot,
                Run<?, ?> build,
                FilePath workspace,
                Launcher launcher,
                TaskListener listener)
                throws IOException, InterruptedException {
            FilePath resolvedPath = workspace.child(expandedPath);
            if (!resolvedPath.exists()) {
                logMessage("Cannot create cache as the path does not exist", listener);
                if (isPathOutsideWorkspace(resolvedPath, workspace) && isMaybeInsideDockerContainer(workspace)) {
                    logMessage(
                            "Note that paths outside the workspace while using the Docker Pipeline plugin are not supported",
                            listener);
                }
                return;
            }

            if (!key.isEmpty()) {
                ExistingCache existingValidCache = resolveExistingValidCache(defaultCachesRoot, workspace, listener);
                if (existingValidCache != null) {
                    logMessage("Skip cache creation as the default cache is still valid", listener);
                    return;
                }

                existingValidCache = resolveExistingValidCache(cachesRoot, workspace, listener);
                if (existingValidCache != null) {
                    logMessage("Skip cache creation as the cache is up-to-date", listener);
                    return;
                }
            }

            ExistingCache existingCache = resolveExistingCache(cachesRoot);
            if (existingCache != null && existingCache.getCompressionMethod() != compressionMethod) {
                logMessage("Delete existing cache as the compression method has been changed", listener);
                existingCache.getCache().deleteRecursive();
            }

            ObjectPath cache = resolveCachePathForCompressionMethod(cachesRoot, compressionMethod);

            logMessage("Creating cache...", listener);
            long cacheCreationStartTime = System.nanoTime();

            try {
                compressionMethod
                        .getCacheStrategy()
                        .cache(resolvedPath, includes, excludes, useDefaultExcludes, cache, workspace);
                if (compressionMethod.isDeprecated()) {
                    listener.getLogger()
                            .println("WARNING: Compression method " + compressionMethod.name() + " is deprecated. Please switch to a supported compression method.");
                }

                if (!key.isEmpty()) {
                    updateSkipCacheTriggerFileHash(cachesRoot, workspace, listener);
                }
                long cacheCreationEndTime = System.nanoTime();
                logMessage(
                        "Cache created in "
                                + Duration.ofNanos(cacheCreationEndTime - cacheCreationStartTime)
                                .toMillis() + "ms",
                        listener);
            } catch (Exception e) {
                logMessage("Failed to create cache", e, listener);
            }
        }

        private boolean isPathOutsideWorkspace(FilePath resolvedPath, FilePath workspace) {
            return !StringUtils.startsWith(resolvedPath.getRemote(), workspace.getRemote());
        }

        private boolean isMaybeInsideDockerContainer(FilePath workspace) {
            return workspace.getChannel() == null || workspace.getChannel() instanceof LocalChannel;
        }

        private void updateSkipCacheTriggerFileHash(ObjectPath cachesRoot, FilePath workspace, TaskListener listener)
                throws IOException, InterruptedException {
            try (TempFile tempFile =
                         WorkspaceHelper.createTempFile(workspace, CACHE_VALIDITY_DECIDING_FILE_HASH_FILE_EXTENSION)) {
                tempFile.get()
                        .write(
                                key,
                                StandardCharsets.UTF_8.displayName());

                ObjectPath skipCacheTriggerFileHashFile = cachesRoot.child(getSkipCacheTriggerFileHashFileName());
                skipCacheTriggerFileHashFile.copyFrom(tempFile.get());
            }
        }
    }

    private void logMessage(String message, Exception exception, TaskListener listener) {
        logMessage(message, listener);
        exception.printStackTrace(listener.getLogger());
    }

    private void logMessage(String message, TaskListener listener) {
        String cacheIdentifier = path;
        if (getCacheName() != null) {
            cacheIdentifier += " (" + getCacheName() + ")";
        }

        listener.getLogger()
                .println("[Cache for " + cacheIdentifier + " with id " + deriveCachePath(path) + "] " + message);
    }

    public HttpResponse doDynamic(StaplerRequest2 req, StaplerResponse2 rsp, @AncestorInPath Job<?, ?> job)
            throws IOException, ServletException, InterruptedException {
        ObjectPath cache = CacheManager.getCachePath(GlobalItemStorage.get().getStorage(), job)
                .child(deriveCachePath(path));

        if (!cache.exists()) {
            req.getView(this, "noCache.jelly").forward(req, rsp);
            return null;
        } else {
            return cache.browse(req, rsp, job, path);
        }
    }

    @Extension
    @Symbol("keyFileCache")
    public static final class DescriptorImpl extends CacheDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.ArbitraryFileCache_displayName();
        }

        @Restricted(NoExternalUse.class)
        public ListBoxModel doFillCompressionMethodItems() {
            ListBoxModel items = new ListBoxModel();
            for (ArbitraryFileCache.CompressionMethod method : ArbitraryFileCache.CompressionMethod.values()) {
                items.add(method.name());
            }

            return items;
        }
    }

    private static class ExistingCache {

        private final ObjectPath cache;
        private final ArbitraryFileCache.CompressionMethod compressionMethod;

        private ExistingCache(ObjectPath cache, ArbitraryFileCache.CompressionMethod compressionMethod) {
            this.cache = cache;
            this.compressionMethod = compressionMethod;
        }

        public ObjectPath getCache() {
            return cache;
        }

        public ArbitraryFileCache.CompressionMethod getCompressionMethod() {
            return compressionMethod;
        }

        public void restore(FilePath target, FilePath workspace) throws IOException, InterruptedException {
            compressionMethod.getCacheStrategy().restore(cache, target, workspace);
        }
    }
}
