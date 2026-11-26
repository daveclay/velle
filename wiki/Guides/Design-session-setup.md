# Setting up a design session

A design session needs three things on one machine: an AI assistant, the Velle compiler, and your spec. This page says what runs where, and how to set it up for an assistant that can run commands and for one that cannot. After reading it you can build what your assistant needs, connect it, and check that it works. [Running a design session](Design-session) shows what a session is like once it does.

## The situation

A team wants to talk through its business rules with an assistant that writes them into a spec and checks every change with the compiler. Someone has to put the compiler where the assistant can reach it. Nothing is installed on a server, and no part of Velle runs when a session is not using it.

## What runs where

There are two setups. A team uses the one its assistant supports.

**Setup A: the assistant runs commands.** Claude Code is one such assistant.

```
the people in the session
        |  talk
        v
the AI assistant  <--  reads the skill: skills/velle-design/SKILL.md and references/
        |  runs a command for each check
        v
scripts/velle  -->  java -jar velle.jar      starts, prints, exits
        |  reads
        v
your files: billing.velle, billing.questions.md, best-practices.md
```

**Setup B: the assistant starts a local MCP server.** A desktop chat app is one such assistant.

```
the people in the session
        |  talk
        v
the AI assistant
        |  calls a tool for each check
        v
velle-authoring-mcp.jar      started by the assistant's app, runs while the app does
```

- **The assistant** is the only part running for the whole session. It listens, writes the Velle, and decides when to call the compiler. Where its model runs is the assistant's business, not Velle's.
- **The [skill](GLOSSARY)** is not a program. It is a folder of instructions and reference pages that the assistant reads.
- **The compiler** is a jar file run by Java on the same machine. It has four commands: `validate`, `compare`, `describe`, and `try`. [Running a design session](Design-session#the-commands) lists what each prints.
- **In setup A the spec is a file** in your folder, and the assistant edits it there.
- **In setup B the spec is text in the conversation.** The server reads and writes no file and keeps nothing between calls. Saving the spec is yours to do.
- **`try` runs an example on a throwaway system** that the compiler builds in memory and discards when the command ends. It never reaches a system you are serving ([Serving a system](Serving-a-system)).
- **Nothing is sent to Velle.** Neither setup uses the network. The assistant itself sees your spec, as it sees everything else in the conversation.

| | Setup A | Setup B |
|---|---|---|
| The assistant must be able to | run a command and read files | start an [MCP](GLOSSARY) server as a local program |
| You build | `skills/velle-design/` with its jar | `velle-authoring-mcp.jar` |
| The spec is | a file the assistant edits | text in the conversation |
| The assistant gets its instructions from | the skill folder | the server's prompt and resources |

An assistant in a browser tab, which can neither run a command on your machine nor start a program there, is served by neither setup.

## What every setup needs

- **Java 21 or later.** Both setups run the compiler from a jar file. `java -version` says which Java a machine has.
- **A clone of the Velle repository,** to build from. The jars are not checked in and are not published anywhere.
- **git, if you want history.** In setup A, when the spec is in a git repository and you ask for it, the assistant makes one git commit per accepted decision. The message holds the decision in the words of the person who made it, the compiler's account of the edit, and the discussion. Without git the session works the same and keeps no history. Only the end-of-session report, every change at once, says what the session did.

## Setup A: an assistant that runs commands

### 1. Build the skill folder

In your clone of the Velle repository:

```bash
./gradlew :compiler:skill
```

- It writes the compiler's jar to `skills/velle-design/scripts/velle.jar`.
- It copies the [Best practices](Best-practices) page into `skills/velle-design/references/`, so the skill carries the best practices of the compiler it runs.

The skill is then the whole `skills/velle-design/` folder: `SKILL.md`, which tells the assistant what to do, `scripts/` with the compiler, and `references/` on how Velle is written.

### 2. Check the compiler runs

From the root of the clone:

```bash
skills/velle-design/scripts/velle validate examples/billing/billing.velle
```

It prints:

```
`examples/billing/billing.velle` validates.
```

If it prints something about Java or a missing jar, see "When it does not work" on this page.

### 3. Put the folder where your assistant looks for skills

**Claude Code** looks for skills in `.claude/skills/` at the root of a project, and in `~/.claude/skills/` for every project of yours. Link the folder there, so a rebuild reaches the assistant:

```bash
mkdir -p .claude/skills
ln -s /path/to/velle/skills/velle-design .claude/skills/velle-design
```

A copy of the folder works too, but has to be copied again after each rebuild.

**claude.ai** takes a skill as a ZIP file of the folder, uploaded where its settings add skills, with code execution turned on. The skill needs Java 21 where the assistant runs its code. When that place has no Java, the assistant says so at the first command and the session cannot run there. Use an assistant on a machine that has Java.

**Another assistant that reads skills as folders with a `SKILL.md` in them** takes the folder wherever it looks for skills. Its own documentation says where.

### 4. Check the assistant uses it

Start the assistant in the folder that holds your spec and say: "Validate billing.velle with the velle-design skill." It should run `scripts/velle validate` and show you the compiler's answer. If it writes Velle without running anything, the skill is not where the assistant looks.

### 5. After you update your clone

Run `./gradlew :compiler:skill` again, so the skill runs the same compiler as the repository. A linked folder needs nothing more. A copied or uploaded folder has to be copied or uploaded again.

## Setup B: an assistant that starts a local MCP server

### 1. Build the server

In your clone of the Velle repository:

```bash
./gradlew :apis:mcp:authoringJar
```

It writes `apis/mcp/build/libs/velle-authoring-mcp.jar`. The jar holds the compiler, the skill's instructions, and its reference pages.

### 2. Tell your assistant how to start it

The assistant's app starts the server as a program and talks to it over the program's standard input and output. Every such app wants the same two things: the command `java`, and the arguments `-jar` and the full path of the jar.

**Claude Desktop** keeps its MCP servers in `claude_desktop_config.json`, opened from its settings under Developer. Add the server and restart the app:

```json
{
  "mcpServers": {
    "velle-authoring": {
      "command": "java",
      "args": ["-jar", "/path/to/velle/apis/mcp/build/libs/velle-authoring-mcp.jar"]
    }
  }
}
```

**Claude Code** can use the server too, in place of the skill:

```bash
claude mcp add velle-authoring -- java -jar /path/to/velle/apis/mcp/build/libs/velle-authoring-mcp.jar
```

**Another assistant** takes the same command and arguments wherever its settings list MCP servers. Its own documentation says where.

### 3. Check the assistant uses it

- The app should list a server named `velle-authoring` with the tools `validate`, `compare`, `describe`, and `try`.
- Say: "Use the velle-authoring tools to validate this spec: `act Member { name: text }`". The answer should include "`draft.velle` validates."
- The server offers the skill's instructions as a prompt named `velle-design`. Choose it where your app lists prompts, or ask the assistant to read the resource `velle-design://SKILL.md` before you start.

### 4. How a session differs

- You paste the spec into the conversation, or start from nothing. The assistant keeps each version in the conversation and sends the text with every tool call.
- At the end, ask for the whole spec and save it to your `.velle` file yourself. The same goes for the questions file.
- git commits are yours to make. The assistant cannot make them.

### 5. After you update your clone

Run `./gradlew :apis:mcp:authoringJar` again and restart the assistant's app, so it starts the new jar.

## What the jars carry

Both jars carry the compiler and Velle's own [extension type](Extension-types) library, `velle.finance`, and no other library. A spec that uses a type from a library of your own cannot be validated by either. Check such a spec where the system is built, with its libraries on the classpath.

## When it does not work

- **"The Velle compiler needs Java 21 or later".** The machine has no `java` command, or an older one. Install Java 21 or later.
- **"The Velle compiler's jar is missing".** The skill folder was not built. Run `./gradlew :compiler:skill` in the Velle repository. If you copied the folder rather than linking it, copy it once more.
- **The spec names a type no library registers.** The skill's jar carries the compiler and Velle's own [extension type](Extension-types) library, `velle.finance`, and nothing else. A spec whose `Order` has a field `price: Euro`, with `Euro` from a library of your own, is refused:

  ```
  - [F1] 'Order.price': no builtin or registered type is named 'Euro' (Reference §5)
  ```

  The skill cannot validate such a spec. Check it where the system is built, with its libraries on the classpath ([Extension types](Extension-types)).
- **On claude.ai, the assistant cannot run anything.** Code execution is off, or the place where it runs code has no Java 21. Turn code execution on in the settings. If Java is what is missing, use an assistant on a machine that has it.
- **The assistant's app cannot start the server.** An app started from the desktop may not find `java`. Write the full path of the `java` command in place of `java`. `which java` prints it on macOS and Linux.
- **The server starts and the app shows no tools.** Start it by hand with `java -jar /path/to/velle-authoring-mcp.jar`. It should wait silently for input. Anything it prints, or an immediate exit, says what is wrong. Press Ctrl-D to stop it.

## Related pages

- [Running a design session](Design-session), what a session is like and what the assistant will and will not do.
- [Comparing specs](Comparing-specs), the report the `compare` command prints.
- [API layer](API-layer), how the authoring server is built.
- [Extension types](Extension-types), the types a library adds to a spec.
