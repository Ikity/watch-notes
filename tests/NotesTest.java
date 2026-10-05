package dev.watchnotes;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import org.json.JSONObject;

public final class NotesTest {
    private static int count;
    private interface Action { void run() throws Exception; }
    private static void check(boolean ok, String message) {
        count++; if (!ok) throw new AssertionError(message);
    }
    private static void fails(Action a, String message) throws Exception {
        boolean rejected=false; try { a.run(); } catch (Exception e) { rejected=true; }
        check(rejected,message);
    }
    private static byte[] archive(String name, byte[] body) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try (ZipOutputStream zip=new ZipOutputStream(out)) { zip.putNextEntry(new ZipEntry(name)); zip.write(body); zip.closeEntry(); }
        return out.toByteArray();
    }
    private static String raw(String title, String body, String... props) {
        StringBuilder s=new StringBuilder(title).append("\n\n").append(body).append("\n\n");
        for (String p : props) s.append(p).append("\n");
        return s.toString();
    }
    private static byte[] tar(Map<String,byte[]> files) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for (Map.Entry<String,byte[]> e : files.entrySet()) {
            byte[] header=new byte[512];
            byte[] name=e.getKey().getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(name,0,header,0,Math.min(name.length,100));
            byte[] size=String.format("%011o",e.getValue().length).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(size,0,header,124,Math.min(size.length,11));
            header[156]=(byte)'0';
            System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII),0,header,257,6);
            for (int i=148;i<156;i++) header[i]=32;
            long sum=0; for (byte b : header) sum+=b&0xFF;
            byte[] check=String.format("%06o\0 ",sum).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(check,0,header,148,8);
            out.write(header); out.write(e.getValue());
            out.write(new byte[(512-e.getValue().length%512)%512]);
        }
        out.write(new byte[1024]);
        return out.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        Note n=new Note(); n.title="Test: \"quotes\"\nNew line"; n.body="# Heading\n\n- [ ] task\n日本語 😀\n---\n";
        n.category="Personal"; n.tags="one, two words"; n.todo=true; n.done=true;
        n.created=Instant.parse("2021-05-01T16:40:00Z").toEpochMilli(); n.updated=n.created+1000;
        Note copy=n.copy();
        check(copy.json().toString().equals(n.json().toString()),"wire roundtrip");
        copy.body="Changed"; check(!n.body.equals(copy.body),"independent copy");
        Note later=n.copy(); later.clock++;
        check(Note.compare(later,n)>0,"logical clock wins");
        Note concurrent=n.copy(); concurrent.revision="ffffffff-ffff-ffff-ffff-ffffffffffff";
        check(Note.compare(concurrent,n)>0,"deterministic concurrent winner");
        check(Note.compare(n,concurrent)<0,"comparison symmetric");
        check(Note.compare(n,n)==0,"same revision equal");
        JSONObject invalid=n.json().put("v",2); fails(() -> Note.from(invalid),"unknown protocol rejected");
        fails(() -> Note.from(n.json().put("id","../bad")),"invalid ID rejected");
        fails(() -> Note.from(n.json().put("clock",Long.MAX_VALUE)),"clock overflow rejected");
        fails(() -> Note.from(n.json().put("created",-1)),"invalid date rejected");
        char[] oversized=new char[200001]; Arrays.fill(oversized,'a');
        fails(() -> Note.from(n.json().put("body",new String(oversized))),"body limit");
        String md=MarkdownNotes.encode(n);
        check(md.contains("completed?: yes\n"),"Joplin todo field");
        check(md.contains("2021-05-01 16:40:00Z"),"Joplin UTC timestamp");
        Note imported=MarkdownNotes.decode(md,"folder/file.md");
        check(imported.title.equals(n.title),"quoted title roundtrip");
        check(imported.body.equals(n.body),"body exact roundtrip");
        check(imported.category.equals(n.category),"category roundtrip");
        check(imported.tags.equals(n.tags),"tags roundtrip");
        check(imported.todo && imported.done,"todo roundtrip");
        check(imported.created==n.created && imported.updated==n.updated,"dates roundtrip");
        check(!imported.id.equals(n.id),"Markdown import creates a new note");
        String fixture="---\ntitle: Take Home Quiz\ncreated: 2021-05-01 16:40:00Z\nupdated: 2021-06-17 23:59:00Z\ntags:\n  - school\n  - math\n  - homework\ncompleted?: no\ndue: 2021-06-18 08:00:00Z\n---\n\n**Prove or give a counter-example**\n";
        Note joplin=MarkdownNotes.decode(fixture,"School/Quiz.md");
        check(joplin.todo && !joplin.done,"official Joplin uncompleted todo");
        check(joplin.category.equals("School"),"directory becomes category");
        check(joplin.tags.equals("school, math, homework"),"official Joplin tags");
        check(joplin.created==n.created,"official Joplin created timestamp");
        check(joplin.body.equals("**Prove or give a counter-example**\n"),"official Joplin body");
        Note plain=MarkdownNotes.decode("\ufeff# Plain\r\nText", "plain.md");
        check(plain.title.equals("plain") && plain.body.equals("# Plain\nText"),"plain Markdown BOM and CRLF");
        check(MarkdownNotes.decode("---\n---\n\ntext","empty.md").body.equals("text"),"empty front matter");
        check(MarkdownNotes.decode("---\ntitle: 'It''s fine'\n---\ntext","n.md").title.equals("It's fine"),"YAML single quotes");
        check(MarkdownNotes.decode("---\ncreated: 2021-05-01 18:40:00+02:00\n---\ntext","n.md").created==n.created,"date timezone offset");
        fails(() -> MarkdownNotes.decode("---\ntitle: missing end","n.md"),"unclosed header rejected");
        fails(() -> MarkdownNotes.decode("---\ncreated: invalid\n---\ntext","n.md"),"bad date reported");
        Note deleted=n.copy(); deleted.deleted=true;
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        MarkdownNotes.exportZip(Arrays.asList(n,deleted),out);
        List<Note> zip=MarkdownNotes.importZip(new ByteArrayInputStream(out.toByteArray()));
        check(zip.size()==1,"trash excluded from Markdown archive");
        check(zip.get(0).body.equals(n.body),"archive body roundtrip");
        check(zip.get(0).title.equals(n.title),"archive title roundtrip");
        check(!MarkdownNotes.safe("../../a/b").contains("/"),"export path sanitized");
        fails(() -> MarkdownNotes.importZip(new ByteArrayInputStream(archive("../escape.md","note".getBytes(StandardCharsets.UTF_8)))),"traversal entry rejected");
        fails(() -> MarkdownNotes.importZip(new ByteArrayInputStream(archive("file.bin",new byte[0]))),"empty note archive rejected");
        fails(() -> MarkdownNotes.importZip(new ByteArrayInputStream(archive("large.md",new byte[1500001]))),"compressed oversize rejected");
        String shared="ESP32-CAM\n\n## GPIO Pins\n\n<img loading=\"lazy\" src=\":/2d89393057e74743bc1354b6b045e315\" alt=\"pinout\">\n\n## [Board](:/42ffc43de46b42d19dfb84b2b270c18b)";
        Note fromJoplin=ShareNotes.receive(shared,null);
        check(fromJoplin.title.equals("ESP32-CAM"),"Joplin share first line is title");
        check(fromJoplin.body.startsWith("## GPIO Pins"),"Joplin share keeps Markdown body");
        check(fromJoplin.body.contains(":/2d89393057e74743bc1354b6b045e315"),"unknown Joplin resource retained");
        check(ShareNotes.receive(ShareNotes.send(fromJoplin),fromJoplin.title).body.equals(fromJoplin.body),"outbound share roundtrip");
        check(ShareNotes.receive("Body alone","Subject").body.equals("Body alone"),"explicit subject respected");
        check(ShareNotes.receive("One line",null).title.equals("One line"),"single-line share");
        check(ShareNotes.receive(md,null).todo,"front matter share metadata");
        String id="d272ea411fc64030846806413ca2a151";
        Note envelope=ShareNotes.receive(id + "\nESP32-CAM\n\n" + fromJoplin.body, null);
        check(envelope.title.equals("ESP32-CAM"),"Joplin envelope ID skipped before title");
        check(envelope.body.equals(fromJoplin.body),"Joplin envelope preserves Markdown body");
        check(ShareNotes.receive(id + "\nESP32-CAM\n\n" + fromJoplin.body,"ESP32-CAM").body.equals(fromJoplin.body),"Joplin subject and ID do not duplicate title");
        check(ShareNotes.receive(id + "\nESP32-CAM\n\n" + fromJoplin.body,id).title.equals("ESP32-CAM"),"Joplin ID subject is not a title");
        check(ShareNotes.receive(id + "\n" + md,null).title.equals(n.title),"front matter after Joplin ID");
        check(ShareNotes.receive("RF1000A\n\n# Обзор\n",null).title.equals("RF1000A"),"Cyrillic example title remains readable");
        check(ShareNotes.receive("Что я беру на вахту\n\n- [ ] кофта с капюшоном",null).body.contains("- [ ]"),"task list from example survives share");
        String hidden=MarkdownPreview.html(fromJoplin,false);
        check(hidden.contains("[image: pinout (resource unavailable)]"),"missing Joplin resource visible as placeholder");
        check(!hidden.contains("<img "),"hidden preview does not render image tags");
        Note image=new Note(); image.body="![Photo](https://example.org/p.png)\n<img src=\"data:image/png;base64,iVBORw0KGgo=\" alt=\"In note\">";
        String shown=MarkdownPreview.html(image,true);
        check(shown.contains("src=\"https://example.org/p.png\""),"HTTPS Markdown image rendered: " + shown);
        check(shown.contains("src=\"data:image/png;base64,iVBORw0KGgo=\""),"embedded HTML image rendered: " + shown);
        check(MarkdownPreview.html(image,false).contains("(hidden)"),"image toggle conceals embedded data");
        image.body="<script>alert(1)</script> <img src=\"javascript:alert(1)\" alt=\"bad\">";
        check(!MarkdownPreview.html(image,true).contains("<script>"),"raw HTML escaped");
        check(!MarkdownPreview.html(image,true).contains("src='javascript:"),"unsafe image scheme rejected");
        image.body="| Pin | Safe? |\n| --- | --- |\n| D0 | yes |\n- [x] Done";
        check(MarkdownPreview.html(image,true).contains("<td>D0</td>"),"Joplin Markdown table rendered");
        check(MarkdownPreview.html(image,true).contains("type=\"checkbox\""),"Markdown checkbox rendered");
        String complex="## Commands\n\n1. First\n   1. Sub\n2. Second\n\n> Quote\n\n---\n\n```bash\nif [ -f x ]; then\n  echo **literal**\nfi\n```\n\nA [link](https://joplinapp.org) and ~~removed~~ and ==highlighted==.\n\nFootnote[^1].\n\n[^1]: Explanation";
        image.body=complex;
        String rich=MarkdownPreview.html(image,true);
        check(rich.contains("<ol>") && rich.contains("<li>Sub"),"nested ordered list");
        check(rich.contains("<blockquote>"),"blockquote");
        check(rich.contains("<hr"),"horizontal rule");
        check(rich.contains("echo **literal**"),"fenced code retains Markdown syntax literally");
        check(rich.contains("<a href=\"https://joplinapp.org\""),"safe hyperlinks");
        check(rich.contains("<del>removed</del>") || rich.contains("<s>removed</s>"),"strikethrough extension");
        check(rich.contains("<mark>highlighted</mark>"),"Joplin mark plugin");
        check(rich.contains("Explanation"),"footnote definition");
        image.body="[[toc]]\n\n# First\n\n## Second";
        check(MarkdownPreview.html(image,true).contains("href=\"#section-2\""),"Joplin table of contents links headings");
        image.body="<img src=\":/2d89393057e74743bc1354b6b045e315\" srcset=\"https://example.org/thumbnail.jpg 300w, https://example.org/full.jpg 1200w\" alt=\"Radio diagram\">";
        check(MarkdownPreview.html(image,true).contains("src=\"https://example.org/thumbnail.jpg\""),"shared HTML srcset HTTPS image fallback");
        check(!MarkdownPreview.html(image,false).contains("<img "),"srcset fallback respects image toggle");
        image.body="<iframe src=\"https://example.org\"></iframe><a href=\"javascript:alert(1)\">bad</a><img src=\"data:text/html;base64,AAAA\">";
        check(!MarkdownPreview.html(image,true).contains("<iframe"),"unsafe raw HTML removed");
        check(!MarkdownPreview.html(image,true).contains("href=\"javascript:"),"unsafe link protocol removed");
        check(!MarkdownPreview.html(image,true).contains("<img "),"unsafe data URI removed");
        ByteArrayOutputStream resourceZip=new ByteArrayOutputStream();
        try (ZipOutputStream resource=new ZipOutputStream(resourceZip)) {
            resource.putNextEntry(new ZipEntry("Notebook/Shared.md")); resource.write(shared.getBytes(StandardCharsets.UTF_8)); resource.closeEntry();
            resource.putNextEntry(new ZipEntry("_resources/2d89393057e74743bc1354b6b045e315.png")); resource.write(new byte[]{1,2,3}); resource.closeEntry();
        }
        Note resolved=MarkdownNotes.importZip(new ByteArrayInputStream(resourceZip.toByteArray())).get(0);
        check(resolved.body.contains("data:image/png;base64,AQID"),"ZIP resources resolve Joplin image IDs");
        check(resolved.body.contains(":/42ffc43de46b42d19dfb84b2b270c18b"),"unmatched links preserved");
        fails(() -> MarkdownNotes.importZip(new ByteArrayInputStream(archive("../bad.png",new byte[]{1}))),"unsafe image archive path rejected");
        String folder="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", note="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", tag="cccccccccccccccccccccccccccccccc";
        String resource="2d89393057e74743bc1354b6b045e315";
        byte[] png=new byte[]{(byte)0x89,'P','N','G',13,10,26,10,1,2,3,4};
        Map<String,byte[]> jex=new LinkedHashMap<>();
        jex.put(folder+".md",raw("Work","", "id: "+folder, "parent_id: ", "type_: 2").getBytes(StandardCharsets.UTF_8));
        jex.put(note+".md",raw("Shopping","- [ ] milk\n\n![Diagram](:/"+resource+")",
            "id: "+note, "parent_id: "+folder, "created_time: 2021-05-01T16:40:00.000Z",
            "updated_time: 2021-06-17T23:59:00.000Z", "user_created_time: 2021-05-01T16:40:00.000Z",
            "user_updated_time: 2021-06-17T23:59:00.000Z", "is_todo: 1", "todo_completed: 0", "type_: 1").getBytes(StandardCharsets.UTF_8));
        jex.put(tag+".md",raw("groceries","", "id: "+tag, "type_: 5").getBytes(StandardCharsets.UTF_8));
        jex.put("dddddddddddddddddddddddddddddddd.md",raw("","", "id: dddddddddddddddddddddddddddddddd", "note_id: "+note, "tag_id: "+tag, "type_: 6").getBytes(StandardCharsets.UTF_8));
        jex.put(resource+".md",raw("diagram.png","", "id: "+resource, "mime: image/png", "file_extension: png", "type_: 4").getBytes(StandardCharsets.UTF_8));
        jex.put("resources/"+resource+".png",png);
        jex.put("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee.md",raw("Secret","hidden", "id: eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "encryption_applied: 1", "type_: 1").getBytes(StandardCharsets.UTF_8));
        jex.put("ffffffffffffffffffffffffffffffff.md",raw("Old","gone", "id: ffffffffffffffffffffffffffffffff", "deleted_time: 1712345678901", "type_: 1").getBytes(StandardCharsets.UTF_8));
        List<Note> jexNotes=JexNotes.importJex(new ByteArrayInputStream(tar(jex)));
        check(jexNotes.size()==1,"JEX imports notes, skips encrypted and trashed");
        Note jn=jexNotes.get(0);
        check(jn.title.equals("Shopping"),"JEX note title");
        check(jn.category.equals("Work"),"JEX folder becomes category");
        check(jn.tags.equals("groceries"),"JEX tag association");
        check(jn.todo && !jn.done,"JEX to-do flag");
        check(jn.created==Instant.parse("2021-05-01T16:40:00Z").toEpochMilli(),"JEX user timestamps");
        check(jn.body.contains("data:image/png;base64,"),"JEX image resource embedded");
        check(Tags.filterNew(jexNotes,JexNotes.importJex(new ByteArrayInputStream(tar(jex)))).isEmpty(),"JEX re-import is a duplicate");
        Map<String,byte[]> nested=new LinkedHashMap<>();
        nested.put("11111111111111111111111111111111.md",raw("Root","","id: 11111111111111111111111111111111","parent_id: ","type_: 2").getBytes(StandardCharsets.UTF_8));
        nested.put("22222222222222222222222222222222.md",raw("Child","","id: 22222222222222222222222222222222","parent_id: 11111111111111111111111111111111","type_: 2").getBytes(StandardCharsets.UTF_8));
        nested.put("33333333333333333333333333333333.md",raw("Deep","body","id: 33333333333333333333333333333333","parent_id: 22222222222222222222222222222222","type_: 1").getBytes(StandardCharsets.UTF_8));
        check(JexNotes.importJex(new ByteArrayInputStream(tar(nested))).get(0).category.equals("Root/Child"),"JEX nested folders join path");
        fails(() -> JexNotes.importJex(new ByteArrayInputStream(tar(Collections.singletonMap("../evil.md",raw("E","b","id: 44444444444444444444444444444444","type_: 1").getBytes(StandardCharsets.UTF_8))))),"JEX traversal entry rejected");
        fails(() -> JexNotes.importJex(new ByteArrayInputStream("not a tar at all............................".getBytes(StandardCharsets.UTF_8))),"non-tar rejected");
        fails(() -> JexNotes.importJex(new ByteArrayInputStream(tar(Collections.singletonMap("empty.md","# no props\n".getBytes(StandardCharsets.UTF_8))))),"JEX entry without metadata rejected");
        check(JexNotes.parseRaw("id: 55555555555555555555555555555555\ntype_: 6","link.md").props.get("type_").equals("6"),"JEX props-only item (tag links) accepted");
        check(Tags.parse(" work, HOME ,,Work ").size()==2,"tag parse trims and dedups case-insensitively");
        check(Tags.format(Arrays.asList("b","a","b")).equals("b, a"),"tag format preserves order without duplicates");
        check(Tags.filter(Arrays.asList("Work","Home","hobby"),"ho").equals(Arrays.asList("hobby","Home")),"tag search filters case-insensitively");
        check(Tags.union("work",Arrays.asList("Home","work")).equals("work, Home"),"tag union merges without duplicates");
        Note a=new Note(); a.title="T"; a.body="B"; a.category="C"; a.tags="x, y"; a.todo=true;
        Note b=new Note(); b.title=" T "; b.body="B"; b.category="C"; b.tags="Y, x"; b.todo=true;
        check(Tags.fingerprint(a).equals(Tags.fingerprint(b)),"duplicate fingerprint ignores whitespace/order/case");
        b.body="Changed";
        check(!Tags.fingerprint(a).equals(Tags.fingerprint(b)),"fingerprint detects body change");
        Note trashed=a.copy(); trashed.deleted=true;
        check(Tags.filterNew(Arrays.asList(trashed),Arrays.asList(a)).size()==1,"trashed content does not block re-import");
        check(Tags.filterNew(Arrays.asList(a),Arrays.asList(b,a)).size()==1,"re-import skips existing content once");
        check(Tags.collect(Arrays.asList(a,trashed)).equals(new TreeSet<>(Arrays.asList("x","y"))),"tag collection skips trash");
        check(Library.categoryMatches("Work",""),"empty filter matches any category");
        check(Library.categoryMatches("","Uncategorized"),"Uncategorized filter matches empty category");
        check(!Library.categoryMatches("Work","Uncategorized"),"Uncategorized filter hides categorized notes");
        check(Library.categoryMatches("Work","Work"),"exact category matches");
        check(!Library.categoryMatches("Work","Home"),"other category does not match");
        check(Library.pageCount(0)==1 && Library.pageCount(20)==1 && Library.pageCount(21)==2,"page count from library size");
        check(Library.pageLabel(0,45).equals("Page 1 of 3"),"page counter label");
        check(Library.pageItem(1,45).equals("Page 2 (21–40 of 45)"),"page selector entry");
        if (args.length > 0) for (String name : new String[]{"jnote", "ex3", "ex4", "ex5", "exBashWordSel"}) {
            File example=new File(args[0],name);
            if (!example.exists()) continue;
            String content=new String(Files.readAllBytes(example.toPath()),StandardCharsets.UTF_8);
            Note sample=ShareNotes.receive("d272ea411fc64030846806413ca2a151\n" + content,null);
            check(sample.title.equals(content.substring(0,content.indexOf('\n')).trim()),"sample title " + name);
            String rendered=MarkdownPreview.html(sample,true);
            check(rendered.contains("</html>") && !rendered.contains("<script>"),"sample Markdown preview " + name);
            if (name.equals("ex4")) check(rendered.contains("type=\"checkbox\""),"real checklist sample");
            if (name.equals("exBashWordSel")) check(rendered.contains("<pre>") && rendered.contains("#!/bin/bash"),"real Bash fence sample");
        }
        System.out.println("NotesTest: " + count + " assertions passed");
    }
}
