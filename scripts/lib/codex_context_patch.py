"""Optional external-harness context boundary for pinned DSH 0.1.2-rc.1."""

def release_source(package):
    """Read the reproducible input instead of a device diagnostic copy."""
    import pathlib
    import tarfile
    root = pathlib.Path(__file__).resolve().parents[2]
    member = f'usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/{package}/lib/index.js'
    with tarfile.open(root / 'downloads/snapshot-arm64.tar.xz', 'r|xz') as archive:
        for entry in archive:
            if entry.name == member:
                return archive.extractfile(entry).read().decode()
    raise RuntimeError('Pinned runtime module missing: ' + package)

def patch_agent_loop(source):
    anchor = '\t\tconst assembly = await this.loopCtx.systemPrompt.assemble(assembleContextFor(this, signal));'
    assert source.count(anchor) == 1, 'Unsupported agent-loop context boundary'
    return source.replace(anchor, '''\t\t// External harnesses own prompt assembly and pre-step context producers.
\t\t// Undefined preserves the complete original DSH path.
\t\tconst delegated = await this.dispatch.waterfall("agent/context-delegation", {
\t\t\tmessages: claimed, ...position, signal
\t\t}, () => Promise.resolve(undefined));
\t\tsignal.throwIfAborted();
\t\tif (delegated !== undefined) return delegated;
''' + anchor)


LINK_OR_COPY_HELPER = '''/**
* dsh-mobile Android publication: app-private directories reject link(2)
* (SELinux EACCES/EPERM; some kernels also report ENOTSUP/EXDEV), which used to
* fail every composer attachment with ATTACHMENT_WRITE_FAILED. Publish the
* already-durable bytes by copying them instead. EEXIST still propagates so the
* callers keep their digest-verified deduplication path.
*/
async function dshMobilePublishLinkOrCopy(source, target) {
\ttry {
\t\tawait link(source, target);
\t} catch (error) {
\t\tif (!(error instanceof Error && "code" in error && (error.code === "EACCES" || error.code === "EPERM" || error.code === "ENOTSUP" || error.code === "EXDEV"))) throw error;
\t\tawait copyFile(source, target, constants.COPYFILE_EXCL);
\t}
}
'''


def patch_attachment_store(source):
    anchor = '\t\tawait ensureDurableDirectory(home, parse(home).root);'
    assert source.count(anchor) == 1, 'Unsupported attachment durability boundary'
    source = source.replace('import { chmod,', 'import { realpath, chmod,', 1)
    source = source.replace(anchor, '''\t\tif (process.platform === "android") {
\t\t\t// Android owns ancestors above filesDir; applications cannot fsync /data.
\t\t\tconst prefix = process.env.TERMUX__PREFIX;
\t\t\tif (!prefix) throw new Error("Android attachment runtime prefix missing");
\t\t\tconst boundary = await realpath(dirname(resolve(prefix)));
\t\t\tawait mkdir(home, { recursive: true, mode: 448 });
\t\t\tconst canonicalHome = await realpath(home);
\t\t\tif (canonicalHome !== boundary && !canonicalHome.startsWith(boundary + "/"))
\t\t\t\tthrow new Error("Android attachment store must be inside application filesDir");
\t\t\tawait ensureDurableDirectory(canonicalHome, boundary);
\t\t} else {
''' + anchor + '''
\t\t}''')

    # 0.2.0-rc.2 note: upstream absorbed the Android link(2) fallback natively
    # (link->rename with dsh-mobile comments in publishImmutableAlias / stage
    # paths), so the link->copyFile helper and its call-site rewrites below
    # are no longer applied. Only the durability boundary above remains.
    return source
