package run.runnable.uploadblogpic;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class UploadBlogPicApplication {

    static R2StorageAPI r2StorageAPI = R2StorageAPI.create(
            "",
            "",
            "",
            "");

    public static final String DIR_NAME = "";
    public static final String IMG_DOMAIN = "";

    //指定你的文件夹路径
    public static final String PATH = "/Users/asher/Desktop/temp/blog";


    public static void main(String[] args) {
        listFilesInDirectory(Path.of(PATH), path -> FilenameUtils.getExtension(path.getFileName().toString()).equalsIgnoreCase("md"))
                .doOnNext(it -> log.info("正在处理文件：{}", it))
                .flatMap(
                        path -> handlerOneBlog(path).subscribeOn(Schedulers.boundedElastic()),
                        2)
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(it -> {
                    //写入新文件
                    String newContent = it.getT1();
                    Path path = it.getT2();

                    String parentPath = path.toFile().getParentFile().getAbsolutePath();
                    String newFile = parentPath + File.separator + "re-" + path.getFileName();

                    try {
                        Files.writeString(Path.of(newFile), newContent);
                        log.info("文件图片替换完成, file:{}", newFile);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .blockLast()
                ;
    }


    /**
     * 处理单篇博客内容
     *
     * @param path 路径
     * @return {@link Mono}<{@link Tuple2}<{@link String}, {@link Path}>>
     */
    @SneakyThrows
    private static Mono<Tuple2<String, Path>> handlerOneBlog(Path path) {
        String fileContent = Files.readString(path, StandardCharsets.UTF_8);
        //进行下载，并且上传到CF中。
        //使用旧的链接做key，新的链接做value
        Map<String, String> originTargetMap = fileContent.lines()
                .map(it -> it.replaceAll("\\!\\[.*\\]", "![]"))
                .flatMap(line -> {
                    String regex = "(\\!\\[.*]\\()(https?:\\/\\/.+\\.(png|jpe?g|webp|gif|svg))(.*\\))";
                    Matcher matcher = Pattern.compile(regex).matcher(line);

                    List<String> picLink = new ArrayList<>();
                    while (matcher.find()) {
                        String linkWithMark = matcher.group();
                        int httpIndex = linkWithMark.indexOf("http");
                        linkWithMark = linkWithMark.substring(httpIndex, linkWithMark.length() - 1);
                        picLink.add(linkWithMark);
                    }
                    return picLink.stream();
                })
                .filter(it -> !it.contains(IMG_DOMAIN))
                .collect(Collectors.toMap(
                        it -> it,
                        link -> downloadAndUpload(link),
                        (e, u) -> e
                ));

        return Mono.just(fileContent)
                .map(it -> replaceLink(it, originTargetMap)).zipWith(Mono.just(path));
    }

    /**
     * 替换链接
     *
     * @param originalContent originalContent
     * @param originTargetMap originTargetMap
     * @return {@link String}
     */
    public static String replaceLink(String originalContent, Map<String, String> originTargetMap){
        return originalContent.lines()
                .map(line -> {
                    if (!originTargetMap.isEmpty()) {
                        final String[] copyLine = {line};
                        originTargetMap.forEach((origin, target) -> {
                            copyLine[0] = copyLine[0].replace(origin, target);
                        });
                        return copyLine[0];
                    }
                    return line;
                })
                .reduce((acc, item) -> acc + "\n" + item)
                .orElse(originalContent);
    }


    /**
     * 下载图片并上传到CF
     *
     * @param link link
     * @return {@link String}
     */
    private static String downloadAndUpload(String link) {
        String extension = link.substring(link.lastIndexOf("."));
        UUID uuid = UUID.randomUUID();
        String identifier = DIR_NAME +"/" + uuid + extension;
        try {
            byte[] download = download(link);
            r2StorageAPI.uploadFile(identifier, new ByteArrayInputStream(download));

            String targetLink = IMG_DOMAIN + identifier;
            log.info("upload success, link:{}", targetLink);
            return targetLink;
        }catch (Exception e){
            log.error("downloadAndUpload error", e);
            return link;
        }
    }

    /**
     * 下载指定的图片链接并获取字节数组
     *
     * @param link link
     * @return {@link byte[]}
     */
    @SneakyThrows
    public static byte[] download(String link){
        URL url = new URL(link);
        InputStream in = new BufferedInputStream(url.openStream());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024 * 1024];
        int n = 0;
        while (-1!=(n=in.read(buf))) {
            out.write(buf, 0, n);
        }
        out.close();
        in.close();
        return out.toByteArray();
    }


    /**
     * 遍历文件夹中的所有文件。包括子文件夹。
     * @param directory directory
     * @param filter    filter
     * @return {@link Flux}<{@link Path}>
     */
    static Flux<Path> listFilesInDirectory(Path directory, Predicate<Path> filter) {
        Mono<Stream<Path>> currentFileMono = Mono.fromCallable(() -> Files.list(directory));
        return currentFileMono
                .subscribeOn(Schedulers.boundedElastic())
                .flux()
                .flatMap(it -> Flux.fromStream(it).filter(filter))
                .flatMap(it -> {
                    if (it.toFile().isDirectory()){
                        return listFilesInDirectory(it, filter);
                    }else {
                        return Flux.just(it);
                    }
                })
                .filter(Files::isRegularFile)
                .subscribeOn(Schedulers.boundedElastic());
    }

}
