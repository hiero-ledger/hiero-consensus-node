import path from "node:path"
import {findFiles, loadWorkflow} from "./util.ts";
import fs from "node:fs/promises"
import type {Job} from "./util.ts";


const WORKFLOWS_DIR = "../../.."
const REPO_ROOT = "../../../../.."
const HARDEN_RUNNER = "step-security/harden-runner"
const INIT_GITHUB_JOB = "pandaswhocode/initialize-github-job"


function hasInitAndHarden(job: Job) {
    let has_init_github_job = false
    let has_harden_runner = false
    if(job.steps) {
        for(let step of job.steps) {
            if(step.uses) {
                if(step.uses.startsWith(INIT_GITHUB_JOB)) has_init_github_job = true
                if(step.uses.startsWith(HARDEN_RUNNER)) has_harden_runner = true
            }
        }
    }
    return has_init_github_job && has_harden_runner
}

const isYaml = (f) => path.extname(f.name) === ".yaml"

async function fileExists(uses: string) {
    let pth = path.normalize(uses)
    pth = path.resolve(REPO_ROOT,pth)
    try {
        let stats = await fs.stat(pth)
        return stats.isFile()
    } catch (e) {
        return false
    }
}

for(let file of await findFiles(WORKFLOWS_DIR, isYaml)) {
    const workflow = await loadWorkflow(file)
    // skip deprecated workflows
    if(workflow.name.startsWith("Deprecated:")) continue


    // find jobs with both init and harden
    for(let [key,job] of Object.entries(workflow.jobs)) {
        if(hasInitAndHarden(job)) {
            console.log(`workflow ${file.name} job: ${key} has both ${HARDEN_RUNNER} and ${INIT_GITHUB_JOB}`)
        }
    }

    // Confirm all uses clauses map to valid files that exist and are readable
    for(let [key,job] of Object.entries(workflow.jobs)) {
        if(job.uses) {
            if(! await fileExists(job.uses)) {
                console.log(`workflow ${file.name} job: ${key} uses: ${job.uses} points to an invalid file`)
            }
        }
    }

    // find uses of install gomplate
    for(let [key,job] of Object.entries(workflow.jobs)) {
        if(job.steps) {
            for(let step of job.steps) {
                if(step.name === 'Install gomplate') {
                    console.log(`workflow ${file.name} job: ${key} has a step to setup gomplate`)
                }
            }
        }
    }
}