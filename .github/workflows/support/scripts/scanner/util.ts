import path from "node:path"
import fs from "node:fs/promises"
import type {Dirent} from "node:fs"
import yaml from 'js-yaml'

export async function findFiles(dir: string, filter: (file: Dirent) => boolean) {
    let workflow_files: Array<Dirent> = []
    for (let entry of await fs.readdir(dir, {withFileTypes: true})) {
        if (filter(entry)) {
            // if(entry.isFile() && path.extname(entry.name) === ".yaml") {
            // console.log(`found ${entry.name}`)
            workflow_files.push(entry)
        }
    }
    return workflow_files
}

type Step = {
    id?: string,
    name?: string,
    uses?: string,
    with?: Record<string, string>
    run?: string,
}
export type Job = {
    name?: string,
    steps?: Array<Step>
}
export type Workflow = {
    name: string,
    jobs: Record<string, Job>
}


export async function loadWorkflow(file:Dirent):Promise<Workflow> {
    const raw = await fs.readFile(path.resolve(file.parentPath, file.name), { encoding: "utf8" })
    return yaml.load(raw) as Workflow
}

