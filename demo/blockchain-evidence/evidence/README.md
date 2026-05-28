Procedure: download Ganache.

Open Ganache, start Quick Start, and note the RPC server port shown in the top bar. It is usually `7545`.

First, open Remix at https://remix.ethereum.org.

Create `EvidenceRegistry.sol` under the `contracts` directory and replace it with the corresponding code.

Open `Solidity Compiler` in the sidebar, go to `Advanced Configurations`, and set `EVM Version` to `Paris`.

Then open `Deploy & Run Transactions` in the sidebar, change `Environment` from `Remix VM` to `Dev`, and select `Ganache Provider`.

Click the blue `Deploy` button. In the pop-up, check that the port is correct, usually `7545`.

After deployment succeeds, Remix displays the contract address. Copy that contract address into the corresponding location in the HTML code, around line 158.

Open a `cmd` window as administrator, then `cd` into the folder containing the HTML file.

Run the following command to start `http-server`:

```cmd
npx http-server -p 8080
```

After the site is served successfully, open http://localhost:8080 in the browser.

On the page, click the `Ganache` link first to test the simulated blockchain.
