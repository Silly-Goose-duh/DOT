# DOT — Android AI Computer-Use Agent

## 1. One-line idea

**DOT is an AI buddy that runs in the background on Android, listens for a wake phrase like "Hey Dot", understands natural-language instructions, sees and interacts with the phone's UI, and completes multi-step tasks on the user's behalf.**

It is **not primarily a chatbot** and **not a traditional voice assistant**.

The goal is:

> **Tell DOT what you want done. DOT figures out how to do it.**

---

# 2. Core Product Vision

Imagine saying:

> "Hey Dot, find my last sem report."

DOT should:

1. Understand the request.
2. Search the user's device/files.
3. Identify the most relevant document.
4. Open it.

Or:

> "Hey Dot, send this doc to Rohan."

DOT should:

1. Identify the current/mentioned document.
2. Open WhatsApp.
3. Find Rohan.
4. Open the conversation.
5. Attach the document.
6. Send it.
7. Ask for clarification if there is ambiguity.

The user should not need to know which application, button, menu, API, or workflow is required.

---

# 3. Product Philosophy

## Traditional assistant

```text
User
  ↓
Question
  ↓
Assistant
  ↓
Answer / instructions
```

## DOT

```text
User
  ↓
Natural-language command
  ↓
DOT understands intent
  ↓
DOT plans
  ↓
DOT observes the phone
  ↓
DOT acts
  ↓
DOT checks result
  ↓
DOT continues / replans
  ↓
Task completed
```

### Core principle

> **The user describes the outcome, not the procedure.**

---

# 4. Example User Experiences

## Example A — Find a document

### User

> "Hey Dot, find my last sem report."

### DOT

```text
Wake
 ↓
Speech → Text
 ↓
Intent: find_document
 ↓
Search local documents
 ↓
Rank candidates
 ↓
Open best match
```

---

## Example B — Send a document

### User

> "Hey Dot, send this doc to Rohan."

### DOT

```text
Identify document
       ↓
Open WhatsApp
       ↓
Find Rohan
       ↓
Open chat
       ↓
Attach document
       ↓
Select document
       ↓
Send
       ↓
Confirm success
```

If multiple Rohans exist:

> "I found Rohan Mathew and Rohan S. Which one?"

---

## Example C — App interaction

> "Hey Dot, open YouTube and search for Coldplay."

DOT:

```text
Open YouTube
 ↓
Find search
 ↓
Type "Coldplay"
 ↓
Submit
 ↓
Verify results
```

---

# 5. What DOT Is

DOT is a combination of:

- AI agent
- Computer-use agent
- Computer vision
- Android automation
- Voice interface
- Natural-language planning
- Tool calling
- Memory
- Context awareness
- Task execution

A useful mental model:

> **LLM = Brain**
>
> **Android APIs = Hands**
>
> **Accessibility = Eyes + Hands**
>
> **Computer vision = Backup eyes**
>
> **Tools = Abilities**
>
> **Memory = Context**
>
> **DOT UI = Face**

---

# 6. Architecture

```text
                         USER
                           │
                           │ "Hey Dot..."
                           ▼
                  ┌─────────────────┐
                  │ Wake Word       │
                  │ Detection       │
                  └────────┬────────┘
                           │
                           ▼
                  ┌─────────────────┐
                  │ Speech-to-Text  │
                  └────────┬────────┘
                           │
                           ▼
              ┌──────────────────────────┐
              │       AGENT BRAIN        │
              │                          │
              │ Intent Understanding     │
              │ Planning                 │
              │ Reasoning                │
              │ Memory                   │
              │ Tool Selection           │
              └────────────┬─────────────┘
                           │
                     Tool Selection
                           │
            ┌──────────────┼──────────────┐
            ▼              ▼              ▼
       Android APIs   Accessibility     Vision
            │              │              │
            └──────────────┼──────────────┘
                           ▼
                    ACTION EXECUTOR
                           │
                           ▼
                        SCREEN
                           │
                           ▼
                       OBSERVE
                           │
                           ▼
                    ┌─────────────┐
                    │  SUCCESS?   │
                    └──────┬──────┘
                       YES │ NO
                           │
                  ┌────────┴────────┐
                  ▼                 ▼
                DONE              REPLAN
```

---

# 7. The Agent Loop

DOT should operate as an iterative agent rather than producing one giant sequence of actions.

```text
PLAN
 ↓
ACTION
 ↓
OBSERVE
 ↓
UNDERSTAND CURRENT STATE
 ↓
SUCCESS?
 ├── YES → NEXT STEP / DONE
 └── NO  → REPLAN
```

Example:

```text
Goal:
"Send my report to Rohan."

Plan:
1. Find report
2. Open WhatsApp
3. Find Rohan
4. Attach report
5. Send

After step 3:

Observation:
"Two contacts named Rohan."

Agent:
Ask user.

User:
"Rohan Mathew."

Agent:
Continue.
```

This makes the system resilient to unexpected UI states.

---

# 8. How DOT Understands the Android Screen

DOT should use a **hybrid approach** rather than relying entirely on computer vision.

## Layer 1 — Android UI semantics

Use Android Accessibility APIs to understand:

- buttons
- text
- input fields
- lists
- clickable elements
- content descriptions
- current window
- app/package information

Example:

```text
Button: Search
Text: Rohan
TextField: Search files
Clickable: true
```

This is generally more reliable than interpreting pixels.

---

## Layer 2 — Android APIs

Whenever Android provides a direct API, use it instead of visually clicking through the UI.

Example:

```text
Find document
    ↓
Document/file APIs
    ↓
Return actual file
```

instead of:

```text
Open file manager
 ↓
Find search icon
 ↓
Tap
 ↓
Type filename
 ↓
Read results
```

APIs should be preferred whenever available.

---

## Layer 3 — Computer Vision

Use vision when semantic UI information is unavailable or insufficient.

```text
Screenshot
    ↓
Vision model
    ↓
Identify UI elements
    ↓
Estimate target location
    ↓
Android gesture
```

Example:

> Vision identifies the attachment icon at a particular screen location.

---

## Layer 4 — OCR

For text-heavy or inaccessible screens:

```text
Screenshot
    ↓
OCR
    ↓
Text + coordinates
    ↓
Target identification
    ↓
Action
```

---

# 9. Tool System

The LLM should **not** be allowed to generate arbitrary Android commands.

Instead, expose controlled tools.

Example tool set:

```text
open_app
close_app
go_back
press_home
tap
long_press
swipe
type_text
press_key
scroll
search_files
open_file
share_file
find_contact
send_message
take_screenshot
read_screen
get_current_app
wait
ask_user
```

The agent chooses tools.

The Android runtime executes them.

---

# 10. Tool Execution Architecture

```text
LLM
 ↓
Tool Call
 ↓
Policy / Safety Validator
 ↓
Android Executor
 ↓
Action
 ↓
Observe Result
 ↓
Return Result to Agent
```

Example:

```json
{
  "tool": "find_contact",
  "arguments": {
    "name": "Rohan"
  }
}
```

The Android layer executes it and returns:

```json
{
  "success": true,
  "matches": [
    {
      "name": "Rohan Mathew",
      "package": "com.whatsapp"
    }
  ]
}
```

---

# 11. Model Architecture

The model should be **replaceable**.

Do not tightly couple DOT to one AI model.

Create an abstraction:

```text
ModelProvider
│
├── CloudProvider
├── OpenAIProvider
├── GeminiProvider
├── ClaudeProvider
├── NexProvider
├── LocalQwenProvider
└── OllamaProvider
```

The rest of DOT should not care which model is underneath.

---

# 12. Does DOT Need Nex?

## Short answer

**No, not for the MVP.**

Nex is interesting because its models are specifically designed around agentic computer interaction, visual feedback, tool use, and long-horizon tasks.

However, DOT should treat Nex as a **potential model backend**, not as the foundation of the application.

The architecture should allow:

```text
DOT Agent Runtime
       │
       ▼
Model Provider
       │
 ┌─────┼─────────┐
 ▼     ▼         ▼
Cloud  Nex      Local
```

Nex can be tested later for computer-use performance.

---

# 13. Recommended Initial AI Architecture

For the first version:

```text
Android
   │
   ▼
FastAPI Backend
   │
   ▼
Agent / LLM
   │
   ▼
Structured Tool Calls
   │
   ▼
Android
```

The cloud model handles:

- intent understanding
- reasoning
- task planning
- ambiguity handling
- tool selection
- complex UI interpretation

The phone handles:

- wake word
- speech capture
- permissions
- accessibility
- UI interaction
- screenshots
- file access
- tool execution

---

# 14. API Design

Potential endpoint:

```http
POST /v1/agent/execute
```

Request:

```json
{
  "session_id": "abc123",
  "user_command": "send this document to Rohan",
  "screen_context": {
    "package": "com.google.android.apps.docs",
    "screen": "document_view"
  },
  "available_tools": [
    "open_app",
    "search_files",
    "tap",
    "type",
    "swipe",
    "back",
    "share"
  ]
}
```

Response:

```json
{
  "type": "plan",
  "actions": [
    {
      "tool": "open_app",
      "arguments": {
        "app": "com.whatsapp"
      }
    },
    {
      "tool": "find_contact",
      "arguments": {
        "name": "Rohan"
      }
    }
  ]
}
```

However, the final architecture should consider whether planning and individual action execution should happen through separate API calls so the agent can observe the phone between steps.

---

# 15. Suggested API Architecture

Instead of:

```text
User → API → entire task → result
```

Prefer:

```text
User
 ↓
/session/start
 ↓
Agent plans
 ↓
/action
 ↓
Android executes
 ↓
/observation
 ↓
Agent reasons
 ↓
/action
 ↓
Android executes
 ↓
...
```

This enables a true observe → reason → act loop.

---

# 16. Android Stack

## Recommended

### Language

**Kotlin**

### UI

**Jetpack Compose**

### Android architecture

- AccessibilityService
- Foreground Service where required
- Android Intents
- Storage Access Framework
- Notification APIs
- PackageManager
- Gesture APIs
- MediaProjection where appropriate
- WorkManager for suitable background work

### Why Kotlin instead of Flutter for the core?

DOT needs deep Android functionality.

Flutter is excellent for UI, but the agent runtime needs native Android access.

Therefore:

> **Native Android first.**

Flutter can still be used later if a cross-platform UI becomes important.

---

# 17. Possible Flutter Architecture

If Flutter is desired:

```text
Flutter UI
    │
    ▼
Platform Channels
    │
    ▼
Kotlin Android Runtime
    │
    ├── Accessibility
    ├── Screen capture
    ├── Gestures
    ├── File APIs
    ├── Intents
    └── Background services
```

This is viable, but adds complexity.

For an Android-first product, Kotlin is the cleaner starting point.

---

# 18. Wake Word

The experience should be:

```text
Phone
 ↓
Local wake-word detector
 ↓
"He​y Dot"
 ↓
Activate listening
 ↓
Speech-to-text
 ↓
Agent
```

The wake-word detector should ideally run locally.

Benefits:

- lower latency
- better privacy
- lower API costs
- no continuous audio upload
- better user experience

---

# 19. Voice Interaction

DOT should not behave like a conventional voice assistant.

It can speak when useful.

Examples:

### Simple command

> "Open WhatsApp."

DOT performs it and may simply say:

> "Done."

### Ambiguous command

> "Send this to Rohan."

DOT:

> "Which Rohan?"

### Dangerous / irreversible action

DOT:

> "This will send the document to Rohan Mathew. Send it?"

### Long-running action

DOT:

> "I'm looking for the latest version of your report."

The voice interaction should be **task-oriented**, not conversational for the sake of conversation.

---

# 20. Memory

DOT can eventually have multiple memory layers.

## Short-term memory

Current task:

```text
User wants:
report → Rohan → WhatsApp
```

## Session memory

Things relevant during the current interaction.

## Long-term memory

User preferences:

```text
Preferred WhatsApp contact for "Rohan"
Preferred folder for college documents
Commonly used apps
Frequent workflows
```

Memory should be transparent and controllable.

---

# 21. Context Awareness

DOT should know useful context such as:

```text
Current app
Current screen
Selected file
Current clipboard
Recent task
Available apps
Relevant notifications
```

Example:

User is viewing a PDF and says:

> "Send this to Rohan."

DOT should interpret **"this"** as the currently viewed document.

This is one of the major differences between DOT and a normal assistant.

---

# 22. Safety Architecture

Because DOT can control a phone, safety is critical.

Introduce action categories.

### Low risk

- open app
- search
- scroll
- navigate
- read information

Can generally execute automatically.

### Medium risk

- modify files
- rename files
- send messages
- share documents

May require confirmation depending on user settings.

### High risk

- payments
- purchases
- deleting important data
- account changes
- security settings

Require explicit confirmation.

Example:

```text
Agent
 ↓
Action risk classifier
 ↓
Low risk → execute
Medium → maybe confirm
High → explicit confirmation
```

---

# 23. Permissions

Likely permissions / capabilities to investigate:

- Microphone
- Accessibility Service
- Notifications
- Foreground Service
- Storage / document access
- Screen capture where required
- Contacts
- Package/app visibility
- Bluetooth or other capabilities only if needed

Permissions should be requested progressively rather than all at once.

---

# 24. Android / Play Store Constraint

This needs to be investigated early.

DOT's core automation functionality may rely heavily on Android Accessibility APIs.

Technical capability does not automatically mean unrestricted Play Store distribution.

The product must be designed around Google's current policies and permitted accessibility-service use cases.

For early development:

> **Prototype → local APK / internal testing**

Then:

> **Policy validation → public distribution**

Do not leave this until the end.

---

# 25. MVP

The first version should be extremely small.

## DOT v0.1

### Wake

> "Hey Dot"

### Task 1

> "Open WhatsApp."

### Task 2

> "Find my semester report."

### Task 3

> "Open YouTube and search for Coldplay."

### Task 4

> "Send this document to Rohan."

### Task 5

> "Go back."

If these work reliably, the core idea is proven.

---

# 26. Suggested Development Phases

## Phase 0 — Research

- Android Accessibility capabilities
- Background microphone limitations
- Screen capture options
- Android app interaction
- Play Store policies
- WhatsApp interaction constraints
- File/document APIs
- Wake-word options

---

## Phase 1 — Android Agent Skeleton

Build:

```text
DOT Android App
 ├── Settings
 ├── Permissions
 ├── Accessibility Service
 ├── Agent Service
 └── Debug console
```

No AI yet.

Prove:

```text
open app
tap
type
swipe
back
read UI
```

---

## Phase 2 — Voice

Add:

```text
Wake word
 ↓
Speech-to-text
 ↓
Command
```

---

## Phase 3 — LLM

Connect:

```text
Command
 ↓
Backend
 ↓
LLM
 ↓
Tool call
 ↓
Android
```

---

## Phase 4 — Agent Loop

Implement:

```text
Plan
 ↓
Act
 ↓
Observe
 ↓
Reason
 ↓
Act
```

---

## Phase 5 — Vision

Add:

```text
Screenshot
 ↓
Vision model
 ↓
UI grounding
 ↓
Action
```

Vision becomes a fallback when Accessibility/UI semantics aren't enough.

---

## Phase 6 — Memory

Add:

- task memory
- user preferences
- contact resolution
- file context
- application context

---

## Phase 7 — Local Intelligence

Experiment with:

- local speech recognition
- local wake word
- local OCR
- local small language model
- local vision model

Goal:

> Reduce cloud dependency.

---

# 27. Potential Future Capabilities

Once the basic agent works:

### Files

> "Organize my Downloads folder."

### Messaging

> "Tell Rohan I'll reach by 8."

### Calendar

> "Schedule a meeting with Rahul tomorrow afternoon."

### Email

> "Find the email about my internship and summarize it."

### Social media

> "Open Instagram and show me the latest post from X."

### Navigation

> "Open Maps and navigate home."

### Productivity

> "Find the presentation I edited yesterday."

### Cross-app workflows

> "Find the invoice in Gmail, download it, and send it to the accountant on WhatsApp."

This is where DOT becomes much more interesting than a conventional assistant.

---

# 28. Long-term Vision

The long-term system could become:

```text
                    DOT
                     │
        ┌────────────┼────────────┐
        ▼            ▼            ▼
      Phone        Files        Cloud
        │            │            │
        ▼            ▼            ▼
      Apps         Data         Services
        │            │            │
        └────────────┼────────────┘
                     ▼
                AI AGENT
                     │
          Understand → Plan → Act
```

The user doesn't interact with individual apps.

They interact with **DOT**.

---

# 29. Key Differentiator

The product should not compete by being:

> "Another AI chatbot."

It should compete on:

### Action

DOT actually does things.

### Context

DOT knows what is currently happening on the phone.

### Cross-app workflows

DOT can move between applications.

### Natural language

Users describe goals rather than procedures.

### Autonomy

DOT decides which tools and actions are necessary.

### Recovery

DOT observes failures and tries another approach.

### Personalization

DOT learns how the user works.

---

# 30. Core Technical Principle

**Never force the AI to use vision when an API can do the job.**

Preferred hierarchy:

```text
1. Direct Android API
        ↓
2. Accessibility / semantic UI
        ↓
3. Intent / deep link
        ↓
4. OCR
        ↓
5. Computer vision
        ↓
6. Human clarification
```

This should improve speed, reliability, battery usage, and cost.

---

# 31. Model Independence

The agent runtime should never assume:

> "The model knows how Android works."

Instead, give the model a controlled environment:

```text
Available tools
Current screen
Current app
Current task
Previous action
Action result
Relevant memory
```

Then let the model reason over that information.

---

# 32. Potential Project Structure

```text
dot/
│
├── android/
│   ├── app/
│   ├── accessibility/
│   ├── agent/
│   ├── voice/
│   ├── vision/
│   ├── tools/
│   ├── permissions/
│   └── ui/
│
├── backend/
│   ├── api/
│   ├── agent/
│   ├── models/
│   ├── tools/
│   ├── memory/
│   └── safety/
│
├── docs/
│   ├── architecture.md
│   ├── agent-loop.md
│   ├── tools.md
│   └── research.md
│
└── README.md
```

---

# 33. Questions to Resolve During Ideation

## Product

- Should DOT be fully hands-free?
- Should it require a wake word every time?
- Should there be a visual confirmation bubble?
- Should DOT speak after every task?
- How autonomous should it be?

## AI

- Which cloud model provides the best tool-use reliability?
- Do we need a dedicated computer-use model?
- Can Nex models help?
- Can a smaller local model handle simple commands?
- Which vision model gives good Android UI grounding?

## Android

- How much can AccessibilityService reliably automate?
- What apps expose usable accessibility trees?
- What apps block or limit automation?
- How should screen capture work?
- How should background execution work?

## Safety

- Which actions require confirmation?
- How do we prevent accidental messages?
- How do we prevent destructive actions?
- How do we handle sensitive data?

## Privacy

- Does the screenshot leave the phone?
- Is voice uploaded?
- Can users run everything locally?
- What data is stored?
- How long is memory retained?

## Business

- Free vs subscription?
- Cloud inference cost?
- Local-only premium mode?
- Personal automation marketplace?
- Custom agent/tool ecosystem?

---

# 34. Initial Hypothesis

The initial hypothesis is:

> **A mobile AI agent can provide a significantly more useful interaction model than a conventional assistant if it can reliably understand the user's intent, observe the current Android state, perform actions across apps, and recover from unexpected UI states.**

The first technical milestone is therefore **not AGI**.

It is:

> **Reliable execution of simple multi-step Android tasks from natural-language commands.**

---

# 35. MVP Success Criteria

DOT v0.1 should be considered successful if a user can say:

> "Hey Dot, find my last semester report."

and DOT can reliably complete the task without the user manually operating the phone.

Then:

> "Hey Dot, send this to Rohan."

and DOT can complete the cross-app workflow, asking for clarification only when necessary.

The system should demonstrate:

```text
Voice
  +
Intent understanding
  +
Planning
  +
Android control
  +
Observation
  +
Recovery
  =
Useful AI Agent
```

---

# 36. Working Definition

> **DOT is a background Android AI agent that turns natural-language goals into real actions across the user's phone. It combines voice, AI reasoning, Android APIs, accessibility, computer vision, memory, and tool execution to operate applications on the user's behalf.**

The ultimate goal is simple:

# "Don't tell me how to do it. Just do it."
