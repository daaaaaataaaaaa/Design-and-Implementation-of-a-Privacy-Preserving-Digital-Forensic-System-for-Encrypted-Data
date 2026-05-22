# Searchable Encryption (Java)

This is a desktop searchable-encryption system for teaching and experiments. You can think of it as a small encrypted cloud drive:

1. A user uploads text or files from the client.
2. The client encrypts the content and keywords locally first.
3. The server stores only ciphertext and searchable indexes.
4. When the user searches for a keyword, the client generates a query token, and the server uses it to match encrypted indexes.
5. After a matching document is downloaded back to the client, the client decrypts it locally.

The project is built with Java Swing, TLS sockets, and MySQL. It is useful for learning how desktop UI, client/server communication, databases, encrypted storage, and keyword retrieval connect into a complete system.

## What Problem This Project Solves

In ordinary document-search systems, the server can usually see plaintext files and plaintext keywords. This project demonstrates another approach:

- File content is encrypted on the client, and the server stores encrypted bytes.
- Keywords are not sent directly to the server; they are transformed into searchable ciphertext.
- During search, the client sends a trapdoor, which is the search token for the current query.
- The server can determine which encrypted keywords match, but it does not directly learn the plaintext keyword.
- Only the client holds the local key after download, so decryption also happens on the client.

> Note: this is a teaching and experimental project focused on understanding the full workflow. The current implementation is not recommended as a production-grade security system.

## Feature Overview

### Users and Sessions

- Supports registration, sign-in, and sign-out.
- The server stores password hashes and salts, not plaintext passwords.
- After sign-in succeeds, a session is created, and later requests check whether the current connection is signed in.

### Document Upload

- Supports direct plaintext input and upload.
- Supports importing one or more files.
- Supports importing folders; the program recursively collects regular files and uploads them in batches.
- Automatically generates a document ID before upload.
- Supports descriptions; descriptions participate in keyword extraction and are stored as encrypted metadata.
- Attempts to extract body-text keywords from text, JSON, PDF, and Word documents.
- Images, audio/video, and binary files can also be uploaded, but search usually depends on the file name and description.

### Keyword Search

- Supports searching documents by keyword.
- The search term is converted to a trapdoor on the client before being sent to the server.
- The server matches encrypted indexes in the `keyword_index` table.
- The client displays the matching document file name, type, size, description, and preview information.
- Image search results support thumbnail previews, with click-to-view full-size images.
- Text document search results try to show decrypted content snippets.
- Besides encrypted-keyword matching, the client also performs fallback fuzzy matching on document IDs and file names to improve usability.

### Document Management

- Supports refreshing the document list.
- Supports single and batch downloads.
- Downloaded documents are decrypted locally on the client and saved to the user-selected location.
- Supports single and batch deletion, and deletes clear related keyword indexes.
- Supports index rebuilding, which is useful for repairing old data or regenerating encrypted keyword indexes.

### Desktop Client Experience

- Builds the desktop UI with Swing and FlatLaf.
- Runs upload, search, download, delete, and other time-consuming tasks in the background to avoid UI freezes.
- Busy-state management disables related buttons while tasks run, reducing repeated-click issues.
- The status bar and progress bar show current task progress.
- The UI dynamically scales fonts, spacing, and button padding according to the window size.

## Start With This Diagram

```text
+--------------+        TLS Socket        +--------------+        JDBC        +--------------+
| Swing client |  ---------------------->  | Java server  |  -------------->  | MySQL DB     |
|              |  <----------------------  |              |  <--------------  |              |
+--------------+                          +--------------+                    +--------------+
       |                                         |                                     |
       |                                         |                                     |
       +-- Generate/load local user keys         +-- Validate signed-in sessions       +-- users
       +-- Encrypt content                       +-- Dispatch request types            +-- documents
       +-- Encrypt keyword indexes               +-- Call repositories                 +-- keyword_index
       +-- Decrypt locally after download        +-- Return unified responses
```

The most important point: the server stores and matches data, while the client handles plaintext and decryption.

## Tech Stack

- Language and build: Java 17, Maven
- Client: Java Swing, FlatLaf
- Network communication: TLS socket, Java object streams
- Database: MySQL 8.x, JDBC
- Document parsing: PDFBox, Apache POI
- Tests: JUnit

The main dependencies are listed in [pom.xml](pom.xml).

## Code Structure

The source code is under `src/main/java/com/bdic` and is split by responsibility:

```text
src/main/java/com/bdic
+-- admin   Client UI, server entry point, and workflow orchestration
+-- crypto  Encryption, password hashing, and client key management
+-- db      Database connection, schema creation, and repositories
+-- model   Network messages, request/response objects, and document models
+-- net     TLS socket creation and certificate loading
+-- text    Text extraction and keyword extraction
```

Tests are located in:

```text
src/test/java/com/bdic
```

### `admin` Package: Entry Points and UI Logic

This package is the best starting point for newcomers because most flows begin here after the user clicks a button.

- `AdminClientApp`
  - Client main-window entry point.
  - Connects to the server, displays sign-in/registration dialogs, and creates the three tabs.
  - The three tabs are `Upload`, `Search`, and `Documents`.
  - If no standalone server is running locally, it tries to start an embedded server automatically.

- `Server`
  - Server entry point.
  - Starts TLS listening, accepts client connections, and reads `NetworkMessage`.
  - Dispatches logic by message type, such as registration, sign-in, upload, search, download, and delete.
  - Returns a unified `ServerResponse`.

- `DocumentServiceClient`
  - Small helper for client-side server access.
  - The UI layer does not operate on sockets directly; it calls methods such as `login`, `upload`, and `search`.
  - Wraps requests into `NetworkMessage` and reads responses.

- `UploadPanelController`
  - Upload-page controller.
  - Selects files/folders, reads plaintext input, and starts background upload tasks.
  - Calls `DocumentOperationService` to encrypt documents and build keyword indexes.

- `SearchPanelController`
  - Search-page controller.
  - Reads the search box, generates search requests, and renders search results.
  - Also handles text previews, image thumbnails, and dynamic scaling of the results area.

- `DocumentsPanelController`
  - Document-management page controller.
  - Handles the document list, downloads, batch downloads, deletion, batch deletion, and index rebuilding.

- `DocumentOperationService`
  - Core client-side document workflow service.
  - Reads files, determines file types, extracts text, extracts keywords, encrypts content, and generates PEKS indexes.
  - Used by both upload and index rebuild flows.

- `UiBusyStateManager`
  - Manages the "task is running" state.
  - Prevents users from clicking search, download, and similar buttons while upload is still running.

- `UiScaleManager`
  - Scales fonts, padding, and layout spacing based on window size.
  - Dynamically generated search results also reapply the current scale.

- `UiComponentFactory`
  - Creates grouped panels and shared button styles to reduce repeated UI code.

- `NativeDialogHelper`
  - Wraps native system file/folder chooser dialogs.

### `crypto` Package: Encryption Logic

- `ClientKeyManager`
  - Generates or loads DES keys and PEKS search public/private keys locally for each user.
  - Keys are stored under `client-keys/` by default and should not be committed to Git.

- `DESUtil`
  - Performs symmetric encryption/decryption for document content and keyword metadata.

- `PEKSUtil`
  - Converts keywords into searchable ciphertext with the search public key.
  - Converts search terms into trapdoors with the search private key.
  - The server uses `test(ciphertext, trapdoor)` to determine whether an encrypted index matches.

- `PasswordUtil`
  - Handles password salts and password hashes.
  - Used for registration and sign-in validation.

### `db` Package: Database Access

- `DatabaseManager`
  - Reads database configuration.
  - Automatically creates the database and tables when the server starts.
  - Adds fields missing from older versions and creates common indexes.

- `UserRepository`
  - Handles user registration and password-hash lookup.

- `EncryptedDataRepository`
  - Handles document save, search, list, download, and delete operations.
  - Writes to the `documents` and `keyword_index` tables.

### `model` Package: Shared Client/Server Data Objects

- `NetworkMessage`
  - Unified network-transfer message object.
  - Contains `type` and `payload`.

- `ServerResponse`
  - Unified server response object.
  - Contains success status, user-facing message, and returned data.

- `LoginRequest`
  - Sign-in/registration request data.

- `DocumentRequest`
  - Request data used for document-ID operations such as download and delete.

- `EncryptedData`
  - Encrypted document entity uploaded to the server.
  - Contains document ID, file name, MIME type, encrypted content, encrypted keyword metadata, and PEKS ciphertext list.

- `DocumentSummary`
  - Lightweight summary used by the document list page.
  - Does not include full encrypted content, making it suitable for list display.

- `SessionInfo`
  - Session information returned after successful sign-in.

- `DocumentIdGenerator`
  - Generates display document IDs.

### `net` Package: TLS Communication

- `SecureSocketProvider`
  - Creates server-side and client-side TLS sockets.
  - Loads the development certificate from `src/main/resources/tls/searchable-encryption-dev.p12`.

### `text` Package: Text and Keyword Processing

- `DocumentTextExtractor`
  - Extracts text from text, JSON, PDF, and Word documents.
  - Returns an empty string on extraction failure instead of interrupting upload.

- `KeywordExtractor`
  - Extracts keywords from descriptions, file names, and body text.
  - Lowercases, deduplicates, and filters tokens that are too short.
  - For CJK text such as Chinese, Japanese, and Korean, it also generates adjacent two-character fragments for local Chinese search.

## Detailed Feature Flows

### 1. Registration and Sign-In

```text
User enters username/password
        |
        v
AdminClientApp shows the authentication dialog
        |
        v
DocumentServiceClient sends REGISTER or LOGIN
        |
        v
Server calls UserRepository
        |
        v
PasswordUtil validates password hash
        |
        v
Server returns ServerResponse and SessionInfo
```

After sign-in succeeds, the client loads or creates local keys for the current user. Later upload, search, and download operations all depend on those keys.

### 2. Upload Flow

```text
User enters text or selects files/folders
        |
        v
UploadPanelController collects upload content
        |
        v
DocumentOperationService reads files and extracts keywords
        |
        v
DESUtil encrypts content
        |
        v
PEKSUtil encrypts keywords with the search public key and creates searchable indexes
        |
        v
DocumentServiceClient sends UPLOAD
        |
        v
Server calls EncryptedDataRepository to write to the database
```

Keyword sources include:

- User-entered descriptions.
- File names.
- Body text from readable files.

To support prefix search, the program stores not only full keywords but also prefix tokens starting at length 2. For example, `searchable` generates index items such as `se`, `sea`, and `sear`.

### 3. Search Flow

```text
User enters a search term
        |
        v
SearchPanelController reads the search box
        |
        v
PEKSUtil generates a trapdoor with the search private key
        |
        v
DocumentServiceClient sends SEARCH
        |
        v
Server runs a PEKS test over keyword_index
        |
        v
Client renders matching documents and previews
```

When displaying search results:

- Text documents try to decrypt and show content snippets.
- Image documents show thumbnails.
- Binary files indicate that the original file should be downloaded for viewing.

### 4. Download Flow

```text
User selects a document and clicks Download
        |
        v
DocumentsPanelController sends DOWNLOAD_DOCUMENT
        |
        v
Server reads the encrypted document
        |
        v
Client decrypts it with DESUtil
        |
        v
Client saves it to the user-selected path
```

The server never decrypts files. Decryption happens only on the client.

### 5. Delete and Rebuild Index

- When deleting a document, the server deletes the document record in `documents`; related indexes in `keyword_index` are removed through foreign-key cascade.
- When rebuilding an index, the client downloads the full document object, restores or re-enters keywords, generates a new PEKS index, and uploads the update.

## Network Protocol

The client and server transfer objects through `NetworkMessage`.

`NetworkMessage` has two main fields:

- `type`: what the request should do.
- `payload`: the data carried by the request.

Current message types include:

- `REGISTER`: register.
- `LOGIN`: sign in.
- `LOGOUT`: sign out.
- `UPLOAD`: upload an encrypted document.
- `SEARCH`: search documents.
- `LIST_DOCUMENTS`: get the document list.
- `DOWNLOAD_DOCUMENT`: download a document.
- `DELETE_DOCUMENT`: delete a document.
- `RESPONSE`: unified server response.

No matter which request the server handles, it eventually returns `ServerResponse`, so the client can process success, failure, and error messages consistently.

## Database Structure

When the server starts, `DatabaseManager` automatically creates the business database and three core tables.

### `users`

Stores user sign-in information:

- `username`: username, primary key.
- `password_hash`: password hash.
- `password_salt`: password salt.
- `created_at`: creation time.

### `documents`

Stores document content and metadata:

- `doc_id`: document primary key.
- `display_doc_id`: display document ID.
- `owner_username`: document owner.
- `file_name`: original file name.
- `mime_type`: MIME type.
- `media_type`: simplified category, such as `text`, `document`, `image`, or `binary`.
- `file_size`: original file size.
- `encrypted_keyword_metadata`: encrypted keyword and description metadata.
- `encrypted_content`: encrypted body content.
- `created_at`: upload time.

### `keyword_index`

Stores searchable encrypted indexes:

- `id`: auto-increment primary key.
- `doc_id`: related document.
- `peks_ciphertext`: searchable ciphertext for the keyword.

## How to Run

### Requirements

- JDK 17
- Maven 3.8+
- MySQL 8.x

### 1. Clone and Compile

```bash
git clone https://github.com/daaaaaataaaaaa/searchable-encryption.git
cd searchable-encryption
mvn clean compile
```

### 2. Configure the Database

`DatabaseManager` reads configuration in this priority order:

```text
JVM system properties > environment variables > defaults
```

Using environment variables for database connection settings, especially passwords, is recommended.

PowerShell example:

```powershell
$env:SE_DB_HOST="127.0.0.1"
$env:SE_DB_PORT="3306"
$env:SE_DB_NAME="searchable_encryption"
$env:SE_DB_USER="root"
$env:SE_DB_PASSWORD="your_password"
```

Configurable items:

- `se.db.host` / `SE_DB_HOST`
- `se.db.port` / `SE_DB_PORT`
- `se.db.name` / `SE_DB_NAME`
- `se.db.user` / `SE_DB_USER`
- `se.db.password` / `SE_DB_PASSWORD`

If you use an IDE, you can also configure these environment variables in the run configuration.

### 3. Run the Client

The simplest option is to run the client directly:

```bash
mvn -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.bdic.admin.AdminClientApp
```

The client connects to `127.0.0.1:12345` by default. If no standalone server is detected, it tries to start an embedded server in the same JVM.

You can also run this directly in an IDE:

```text
com.bdic.admin.AdminClientApp.main()
```

### 4. Run the Server Separately, Optional

If you want to observe communication between separate client and server processes, start the server first:

```bash
mvn -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.bdic.admin.Server
```

Then start the client.

## Usage Steps

### First Use

1. Confirm that MySQL is running.
2. Configure database environment variables.
3. Start the client.
4. Choose `Register` in the pop-up dialog to create a user.
5. After registration succeeds, sign in to enter the main UI.

### Upload Plain Text

1. Open the `Upload` tab.
2. Enter a description or keywords in `Description`.
3. Enter body text in the text box.
4. Click `Upload Document`.
5. After upload succeeds, refresh the `Documents` tab to view it.

### Upload Files or Folders

1. Open the `Upload` tab.
2. Click `Import`.
3. Select `Import Files` or `Import Folder`.
4. Enter a description, if needed.
5. Click `Upload Document`.

### Search Documents

1. Open the `Search` tab.
2. Enter a keyword, file-name prefix, or document-ID fragment.
3. Click `Search` or press Enter.
4. View matching documents in the result list.

### Download or Delete Documents

1. Open the `Documents` tab.
2. Click `Refresh List`.
3. Select one or more documents.
4. Click `Download`, `Delete`, or `Rebuild Index`.

## Development Commands

```bash
mvn compile
mvn test
mvn package
```

Common notes:

- `mvn compile`: compile only the main code.
- `mvn test`: compile and run tests.
- `mvn package`: compile, test, and package.

## Test Coverage

Current tests are under `src/test/java/com/bdic`:

- `CryptoTest`: encryption utility tests.
- `DatabaseRepositoryTest`: database repository tests.
- `DocumentIdGeneratorTest`: document ID generation tests.
- `KeywordExtractorTest`: keyword extraction tests.
- `SecureSocketProviderTest`: TLS socket configuration tests.

## FAQ

### 1. Database connection fails on startup

Check:

- Whether MySQL is running.
- Whether `SE_DB_HOST`, `SE_DB_PORT`, `SE_DB_USER`, and `SE_DB_PASSWORD` are correct.
- Whether the current database user has permission to create databases and tables.

### 2. Large file upload fails

Large files can be affected by MySQL settings such as `max_allowed_packet`. Increase the related MySQL limits, or test first with smaller files.

### 3. Why the server cannot directly see file content

File content is encrypted with `DESUtil` on the client before upload. The server stores `encrypted_content`, and it cannot recover plaintext without the client's local key.

### 4. Why search can still find matches

During upload, the client converts keywords to encrypted indexes with the PEKS search public key. During search, the client converts the search term into a trapdoor with the PEKS search private key. The server runs a PEKS test with the trapdoor and index, so it can determine whether there is a match.

### 5. What is the `client-keys/` directory?

This is where the client stores local DES keys and PEKS private keys for users. These keys determine whether you can decrypt documents you uploaded and generate trapdoors that match indexes. Do not commit this directory to Git, and do not delete it casually.

## Known Boundaries

- Java object streams are suitable for teaching and internal-network experiments. Public network environments should use a more standard protocol and serialization format.
- The current certificate is for development. Production environments need replacement certificates and key-management plans.
- The current `PEKSUtil` uses a pure-JDK RSA trapdoor permutation to express PEKS public keys, private keys, trapdoors, and tests. Production-grade standard PEKS usually requires bilinear-pairing libraries such as JPBC and a complete security audit.
- This project emphasizes workflow completeness and readability; it is not equivalent to a security-audited production encryption system.
- Large-file upload capacity depends on database, memory, and network configuration.

## Security and Commit Advice

- Do not commit `client-keys/`, `target/`, local certificates, database passwords, or other key files.
- Pass database passwords through environment variables or JVM parameters instead of hard-coding them in public repositories.
- If keys or certificates ever appeared in commit history, clean the Git history and rotate the keys.

## Recommended Reading Order

If this is your first time reading the project, use this order:

1. `AdminClientApp`: understand how the program starts and how the three pages are created.
2. `UploadPanelController`: follow one upload from button click to background task.
3. `DocumentOperationService`: understand how files become encrypted documents.
4. `DocumentServiceClient` and `NetworkMessage`: understand how the client sends requests.
5. `Server`: understand how the server receives and dispatches requests.
6. `EncryptedDataRepository`: understand how data is finally written to the database.
7. `SearchPanelController`: understand how search results return and render.

This order makes it easier to build an overall understanding before diving into the encryption details.
